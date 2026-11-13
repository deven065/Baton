#!/usr/bin/env python3
"""
Benchmarks Baton's extraction: offline rules vs. language models, on both synthetic projects.

For each dataset (Project Annapurna, Project Godavari) it creates a fresh project in the demo workspace, uploads
the sources, starts the handover, then reconstructs it once per configuration and scores every run with
demo/evaluate.py: recall on the 25 planted facts, precision and trap hits (Godavari has an exhaustive answer key),
the trust checks, the model check verdicts, tokens, time and list-price cost.

Usage (against a local server started with DEMO_LOCKED=false, so the benchmark may change AI settings):
    export GROQ_API_KEY=...            # your own key; it is sent only to your Baton server
    python demo/benchmark.py http://localhost:8080 \\
        --configs offline,groq:llama-3.3-70b-versatile,groq:openai/gpt-oss-120b \\
        --report demo/benchmark-report.md

"offline" needs no key. Offline runs first: offline mode replays recorded model answers for identical prompts,
so running it after a model would not measure the rules alone. A model run that repeats an identical earlier
prompt is served from the response cache and shows 0 tokens; the report marks it. At the end the workspace's AI
settings are removed again if there were none before.
"""
import argparse
import datetime
import json
import mimetypes
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import evaluate as ev  # noqa: E402

HERE = pathlib.Path(__file__).resolve().parent

# List prices in US dollars per million tokens (input, output), from the providers' pricing pages in November 2026.
# Groq's free tier costs nothing up to its rate limits; these show what the same run costs on a paid plan.
PRICES = {
    "llama-3.3-70b-versatile": (0.59, 0.79),
    "llama-3.1-8b-instant": (0.05, 0.08),
    "openai/gpt-oss-120b": (0.15, 0.75),
    "openai/gpt-oss-20b": (0.10, 0.50),
    "gpt-4o-mini": (0.15, 0.60),
}
KEY_ENV = {"GROQ": "GROQ_API_KEY", "OPENAI": "OPENAI_API_KEY", "OPENROUTER": "OPENROUTER_API_KEY"}


def multipart(api, path, token, fields, file_path):
    boundary = uuid.uuid4().hex
    body = bytearray()
    for k, v in fields.items():
        body += f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
    name = pathlib.Path(file_path).name
    ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
    body += (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
             f'Content-Type: {ctype}\r\n\r\n').encode()
    body += pathlib.Path(file_path).read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(api + path, data=bytes(body), method="POST")
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    try:
        with urllib.request.urlopen(req, timeout=120) as res:
            return json.loads(res.read().decode())
    except urllib.error.HTTPError as e:
        sys.exit(f"Upload {name} failed: HTTP {e.code} {e.read().decode()[:300]}")


def wait_job(api, token, job, minutes=20):
    deadline = time.time() + minutes * 60
    while time.time() < deadline:
        body = ev.call(api, f"/api/jobs/{job}", token)
        if body["status"] in ("SUCCEEDED", "FAILED"):
            return body
        time.sleep(2)
    sys.exit(f"Job {job} did not finish in {minutes} minutes")


def user_ids(api, admin):
    people = ev.call(api, "/api/directory", admin)
    people = people.get("items", people) if isinstance(people, dict) else people
    return {p["email"]: p["id"] for p in people}


def prepare(api, workspace, password, admin, dataset, stamp):
    """Creates a project with the dataset's people and sources, and the handover. Returns (lead token, handover id)."""
    spec = ev.load_spec(dataset)
    ids = user_ids(api, admin)
    lead_email = spec["lead"]
    project = ev.call(api, "/api/projects", admin, {
        "name": f"Benchmark: {spec['dataset']} {stamp}", "description": "Created by demo/benchmark.py",
        "leadUserId": ids[lead_email]})["id"]
    for email in spec["members"]:
        if email != lead_email:
            ev.call(api, f"/api/projects/{project}/members", admin, {"userId": ids[email], "role": "MEMBER"})
    lead = ev.login(api, workspace, lead_email, password)
    jobs = []
    for s in spec["sources"]:
        fields = {"type": s["type"], "title": s["title"], "sensitivity": s["sensitivity"], "authorisationAttested": "true"}
        if s.get("groups"):
            fields["aclGroups"] = s["groups"]
        jobs.append(multipart(api, f"/api/projects/{project}/sources", lead, fields, HERE / dataset / s["file"])["jobId"])
    for j in jobs:
        if wait_job(api, lead, j)["status"] != "SUCCEEDED":
            sys.exit(f"A {dataset} source failed to load (job {j})")
    d = spec["departing"]
    handover = ev.call(api, f"/api/projects/{project}/handovers", lead, {
        "departingUserId": ids[d["email"]], "title": d["title"], "scenario": "PLANNED", "aliases": d["aliases"]})["id"]
    print(f"{spec['dataset']}: project {project}, handover {handover}, {len(jobs)} sources loaded")
    return lead, handover


def set_ai(api, admin, config):
    if config == "offline":
        try:
            ev.call(api, "/api/admin/ai", admin, method="DELETE")
        except SystemExit:
            pass  # nothing configured
        return
    provider, model = config.split(":", 1)
    provider = provider.upper()
    key = os.environ.get(KEY_ENV.get(provider, ""), "")
    if not key:
        sys.exit(f"Set {KEY_ENV.get(provider, 'the API key')} to benchmark {config}")
    ev.call(api, "/api/admin/ai", admin, {"provider": provider, "model": model, "apiKey": key, "enabled": True}, method="PUT")


def seconds(run):
    try:
        a = datetime.datetime.fromisoformat(run["startedAt"].replace("Z", "+00:00"))
        b = datetime.datetime.fromisoformat(run["finishedAt"].replace("Z", "+00:00"))
        return (b - a).total_seconds()
    except (KeyError, TypeError, ValueError, AttributeError):
        return None


def cost(model, stats):
    price = PRICES.get(model)
    if not price or not stats.get("tokensIn"):
        return None
    return stats["tokensIn"] / 1e6 * price[0] + stats.get("tokensOut", 0) / 1e6 * price[1]


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("api", nargs="?", default="http://localhost:8080")
    p.add_argument("--datasets", default="annapurna,godavari")
    p.add_argument("--configs", default="offline")
    p.add_argument("--workspace", default="demo")
    p.add_argument("--report", default=str(HERE / "benchmark-report.md"))
    a = p.parse_args()
    api = a.api.rstrip("/")
    password = os.environ.get("DEMO_PASSWORD", "annapurna-demo")
    configs = [c.strip() for c in a.configs.split(",") if c.strip()]
    configs.sort(key=lambda c: c != "offline")  # offline first, see above
    stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")

    admin = ev.login(api, a.workspace, "admin@annapurna.test", password)
    had_ai = (ev.call(api, "/api/admin/ai", admin) or {}).get("settings", {}).get("configured", False)
    rows, details = [], []
    try:
        for dataset in [d.strip() for d in a.datasets.split(",") if d.strip()]:
            lead, handover = prepare(api, a.workspace, password, admin, dataset, stamp)
            for config in configs:
                set_ai(api, admin, config)
                job = ev.call(api, f"/api/handovers/{handover}/reconstruct", lead, body={})["jobId"]
                status = wait_job(api, lead, job)["status"]
                r = ev.evaluate(api, a.workspace, password, dataset, handover)
                stats = r["run"].get("stats") or {}
                model = config.split(":", 1)[1] if ":" in config else "offline rules"
                pr = r["precision"]
                mc = r["modelCheck"]
                rows.append({
                    "dataset": r["spec"]["dataset"], "config": config, "model": model, "status": status,
                    "found": r["found"], "facts": len(r["recall"]),
                    "precision": pr and pr["precision"], "shown": pr and pr["shown"], "correct": pr and pr["correct"],
                    "traps": pr and sum(1 for _, fell in pr["traps"] if fell), "trapTotal": pr and len(pr["traps"]),
                    "checksPassed": sum(1 for _, ok, _ in r["checks"] if ok), "checks": len(r["checks"]),
                    "claims": len(r["claims"]), "disputed": mc.get("UNSUPPORTED", 0) + mc.get("PARTIAL", 0),
                    "checked": sum(mc.values()), "tokensIn": stats.get("tokensIn", 0), "tokensOut": stats.get("tokensOut", 0),
                    "seconds": seconds(r["run"]), "cost": cost(model, stats),
                    "failedBatches": stats.get("batchesFailed", 0), "batches": stats.get("batches", 0),
                })
                details.append(f"## {r['spec']['dataset']} — {config}\n\n" + ev.report(api, r).split("\n", 2)[2])
                row = rows[-1]
                print(f"  {config}: recall {row['found']}/{row['facts']}"
                      + (f", precision {row['correct']}/{row['shown']}, traps {row['traps']}/{row['trapTotal']}" if pr else "")
                      + f", tokens {row['tokensIn']}+{row['tokensOut']}, {status}")
    finally:
        if not had_ai:
            set_ai(api, admin, "offline")

    def fmt_pct(n, d):
        return f"{n}/{d} ({100 * n / d:.0f}%)" if d else "–"

    lines = [
        "# Baton extraction benchmark",
        "",
        f"Run {stamp} against `{api}` with `demo/benchmark.py`. Recall: planted facts found (25 per project). "
        "Precision (Project Godavari only, which has an exhaustive answer key): claims shown as fact that are correct. "
        "Traps: jokes, hypotheticals, rumours, rejected ideas, sarcasm and superseded values shown as fact (lower is better). "
        "Model check: claims a second model pass disputed (unsupported or partial). Cost uses list prices; Groq's free tier costs $0.",
        "",
        "| Project | Configuration | Recall | Precision | Traps shown as fact | Trust checks | Claims | Model check disputed | Tokens in / out | Time | List-price cost |",
        "|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        tokens = f"{r['tokensIn']:,} / {r['tokensOut']:,}" if r["tokensIn"] else ("0 (offline)" if r["config"] == "offline" else "0 (cached)")
        lines.append(" | ".join([
            f"| {r['dataset']}", f"`{r['config']}`", fmt_pct(r["found"], r["facts"]),
            fmt_pct(r["correct"], r["shown"]) if r["shown"] is not None else "–",
            f"{r['traps']}/{r['trapTotal']}" if r["trapTotal"] else "–",
            f"{r['checksPassed']}/{r['checks']}", str(r["claims"]),
            f"{r['disputed']} of {r['checked']}" if r["checked"] else "–", tokens,
            f"{r['seconds']:.0f} s" if r["seconds"] is not None else "–",
            f"${r['cost']:.4f}" if r["cost"] is not None else ("$0" if r["config"] == "offline" else "–"),
        ]) + " |")
        if r["failedBatches"]:
            lines.append(f"| | ↳ {r['failedBatches']} of {r['batches']} batches failed (rate limits); recall is a lower bound | | | | | | | | | |")
    text = "\n".join(lines) + "\n\n" + "\n".join(details)
    pathlib.Path(a.report).write_text(text, encoding="utf-8")
    pathlib.Path(a.report).with_suffix(".json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    print(f"\nReport written to {a.report}")


if __name__ == "__main__":
    main()
