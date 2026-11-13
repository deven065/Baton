#!/usr/bin/env python3
"""
Scores a reconstructed handover against a planted-facts answer key and checks the trust rules: no injected text,
no raw secrets, nothing restricted shown to people without clearance.

Two datasets ship with Baton:
    annapurna   25 planted facts (recall) — the original demo
    godavari    25 harder facts plus an exhaustive answer key and 7 traps (recall, precision, trap hits)

Usage:
    bash demo/load-demo.sh                      # load Project Annapurna and reconstruct
    python demo/evaluate.py                     # score the newest handover
    python demo/evaluate.py https://baton-api-z29j.onrender.com --report demo/evaluation-report.md
    python demo/evaluate.py --dataset godavari --handover <id>

Options: --dataset, --handover ID, --workspace demo, --report PATH. The demo password comes from DEMO_PASSWORD
(default annapurna-demo). Needs only the Python standard library. Exit code 1 if a check fails.
To compare models, use demo/benchmark.py, which builds on the functions here.
"""
import argparse
import datetime
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
SHOWN_AS_FACT = ("VERIFIED", "SINGLE_SOURCE")


def load_spec(dataset):
    return json.loads((HERE / dataset / "planted-facts.json").read_text(encoding="utf-8"))


def call(api, path, token=None, body=None, method=None):
    req = urllib.request.Request(api + path, method=method or ("POST" if body is not None else "GET"))
    req.add_header("Accept", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, data, timeout=120) as res:
            text = res.read().decode()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        sys.exit(f"{req.method} {path} failed: HTTP {e.code} {e.read().decode()[:300]}")


def can_open(api, token, path):
    """False when the API refuses (403/404): a person who cannot open the handover cannot see its facts either."""
    req = urllib.request.Request(api + path, headers={"Authorization": "Bearer " + token, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60):
            return True
    except urllib.error.HTTPError as e:
        if e.code in (403, 404):
            return False
        raise


def login(api, workspace, email, password):
    return call(api, "/api/auth/login", body={"workspace": workspace, "email": email, "password": password})["token"]


def all_claims(api, token, handover):
    items, page = [], 0
    while True:
        body = call(api, f"/api/handovers/{handover}/claims?size=200&page={page}", token)
        items += body["items"]
        if len(body["items"]) < 200:
            return items
        page += 1


def claim_text(c):
    parts = [c.get("title") or "", c.get("body") or ""]
    parts += [e.get("quote") or "" for e in c.get("evidence", [])]
    parts += [str(v) for k, v in (c.get("attributes") or {}).items() if v is not None and not k.startswith("modelCheck")]
    return " ".join(parts).lower()


def has_all(text, words):
    return all(w.lower() in text for w in words)


def has_none(text, words):
    return not any(w.lower() in text for w in words)


def score_recall(spec, claims, gaps):
    """Returns [(fact, found, matched_by)] for every planted fact."""
    results = []
    for f in spec["facts"]:
        if f["kind"] == "gap":
            hit = next((g["question"] for g in gaps if has_all(g["question"].lower(), f["all"])), None)
        elif f["kind"] == "conflict":
            hits = [c for c in claims if c["status"] == "CONFLICTING" and has_all(claim_text(c), f["all"])]
            hit = f"{len(hits)} conflicting claims" if len(hits) >= 2 else None
        else:
            types = set(f.get("types") or [])
            hit = next((f'{c["type"]} "{c["title"]}" ({c["status"]})' for c in claims
                        if (not types or c["type"] in types) and has_all(claim_text(c), f["all"])), None)
        results.append((f, hit is not None, hit))
    return results


def trap_hit(spec, c):
    text = claim_text(c)
    for t in spec.get("traps", []):
        types = set(t.get("types") or [])
        if (not types or c["type"] in types) and has_all(text, t["all"]) and has_none(text, t.get("none", [])):
            return t
    return None


def score_precision(spec, claims):
    """Only for datasets with an exhaustive answer key ('alsoTrue'). Counts claims shown as fact."""
    if "alsoTrue" not in spec:
        return None
    shown = [c for c in claims if c["status"] in SHOWN_AS_FACT]
    correct, wrong, unmatched = [], [], []
    keys = [f["all"] for f in spec["facts"]] + spec["alsoTrue"]
    for c in shown:
        t = trap_hit(spec, c)
        if t:
            wrong.append((c, t))
        elif any(has_all(claim_text(c), k) for k in keys):
            correct.append(c)
        else:
            unmatched.append(c)
    # Traps caught: shown as something other than fact (inferred, conflicting, stale) or not extracted at all.
    trapped_ids = {t["id"] for _, t in wrong}
    return {
        "shown": len(shown), "correct": len(correct), "wrong": wrong, "unmatched": unmatched,
        "precision": (len(correct) / len(shown)) if shown else None,
        "traps": [(t, t["id"] in trapped_ids) for t in spec.get("traps", [])],
    }


def trust_checks(api, workspace, password, spec, lead, handover, claims, gaps):
    checks = []
    everything = json.dumps(claims) + json.dumps(gaps)
    injected = [t for t in spec["checks"]["neverInClaims"] if t.lower() in everything.lower()]
    checks.append(("Injected instructions never become claims", not injected, ", ".join(injected) or "none found"))

    chunk_ids = {e["chunkId"] for c in claims for e in c.get("evidence", []) if e.get("chunkId")}
    stored = everything + "".join(json.dumps(call(api, f"/api/chunks/{cid}", lead)) for cid in chunk_ids)
    secrets = spec["checks"]["secretsNeverStored"]
    leaked = [s for s in secrets if s.lower() in stored.lower()]
    checks.append(("Planted secrets and personal data are redacted", not leaked,
                   f"{len(leaked)} of {len(secrets)} found in stored text" if leaked
                   else f"0 of {len(secrets)} found in claims, questions or {len(chunk_ids)} cited chunks"))

    bad_quotes = sum(1 for c in claims if c["status"] in SHOWN_AS_FACT
                     and not any(e.get("matchScore", 0) >= 0.999 for e in c.get("evidence", [])))
    shown = sum(1 for c in claims if c["status"] in SHOWN_AS_FACT)
    checks.append(("Every claim shown as fact has an exact quote", bad_quotes == 0, f"{shown - bad_quotes} of {shown}"))

    for email, words in spec["checks"]["hiddenFrom"].items():
        token = login(api, workspace, email, password)
        if not can_open(api, token, f"/api/handovers/{handover}/claims?size=1"):
            checks.append((f"Restricted facts hidden from {email}", True, "cannot open this handover at all"))
            continue
        seen = json.dumps(all_claims(api, token, handover)) + json.dumps(call(api, f"/api/handovers/{handover}/gaps", token))
        leaks = [w for w in words if w.lower() in seen.lower()]
        checks.append((f"Restricted facts hidden from {email}", not leaks, ", ".join(leaks) or "nothing restricted visible"))
    return checks


def model_check_summary(claims):
    verdicts = {}
    for c in claims:
        v = (c.get("attributes") or {}).get("modelCheck")
        if v:
            verdicts[v] = verdicts.get(v, 0) + 1
    return verdicts


def evaluate(api, workspace, password, dataset, handover=None):
    """Scores one handover. Returns a dict with everything the reports need."""
    spec = load_spec(dataset)
    lead = login(api, workspace, spec.get("lead", "neha@annapurna.test"), password)
    if not handover:
        newest = None
        for project in call(api, "/api/projects", lead):
            for h in call(api, f'/api/projects/{project["id"]}/handovers', lead):
                if newest is None or h["createdAt"] > newest["createdAt"]:
                    newest = h
        if newest is None:
            sys.exit("No handover found. Run demo/load-demo.sh first.")
        handover = newest["id"]
    claims = all_claims(api, lead, handover)
    gaps = call(api, f"/api/handovers/{handover}/gaps", lead)
    run = call(api, f"/api/handovers/{handover}/runs/latest", lead) or {}
    recall = score_recall(spec, claims, gaps)
    return {
        "spec": spec, "dataset": dataset, "handover": handover, "claims": claims, "gaps": gaps, "run": run,
        "recall": recall, "found": sum(1 for _, ok, _ in recall if ok),
        "precision": score_precision(spec, claims),
        "checks": trust_checks(api, workspace, password, spec, lead, handover, claims, gaps),
        "modelCheck": model_check_summary(claims),
    }


def pct(n, d):
    return f"{100 * n / d:.0f}%" if d else "–"


def report(api, r):
    spec, results, p = r["spec"], r["recall"], r["precision"]
    stats = (r["run"].get("stats") or {})
    lines = [
        f"# Baton evaluation: {spec['dataset']}",
        "",
        f"- Run: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M')} against `{api}`, handover `{r['handover']}`",
        f"- Extraction model: `{r['run'].get('model') or 'unknown'}`",
        f"- **Recall: {r['found']} of {len(results)} planted facts ({pct(r['found'], len(results))})**",
    ]
    if p:
        lines.append(f"- **Precision: {p['correct']} of {p['shown']} claims shown as fact are correct "
                     f"({pct(p['correct'], p['shown'])})** · {len(p['wrong'])} fell for a trap · {len(p['unmatched'])} unmatched")
    if r["modelCheck"]:
        lines.append("- Model check: " + ", ".join(f"{v} {k.lower()}" for k, v in sorted(r["modelCheck"].items())))
    elif stats.get("modelCheckSkipped"):
        lines.append(f"- Model check: skipped (offline mode has no model), {stats['modelCheckSkipped']} claims unchecked")
    if stats.get("tokensIn"):
        lines.append(f"- Tokens: {stats.get('tokensIn', 0):,} in, {stats.get('tokensOut', 0):,} out")
    lines += [f"- Claims: {len(r['claims'])} · exit questions: {len(r['gaps'])}", "",
              "| Fact | What was planted | Source | Found | Matched by |", "|---|---|---|---|---|"]
    for f, ok, hit in results:
        lines.append(f'| {f["id"]} | {f["what"]} | {f["source"]} | {"yes" if ok else "**no**"} | {(hit or "").replace("|", "/")} |')
    if p:
        lines += ["", "| Trap | What it tests | Result |", "|---|---|---|"]
        for t, fell in p["traps"]:
            lines.append(f'| {t["id"]} | {t["what"]} | {"**shown as fact**" if fell else "avoided"} |')
        if p["unmatched"]:
            lines += ["", "Claims shown as fact that match nothing in the answer key (counted against precision):", ""]
            lines += [f'- {c["type"]}: {c["title"]}' for c in p["unmatched"]]
    lines += ["", "| Check | Result | Detail |", "|---|---|---|"]
    for name, ok, detail in r["checks"]:
        lines.append(f'| {name} | {"pass" if ok else "**FAIL**"} | {detail} |')
    return "\n".join(lines) + "\n"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("api", nargs="?", default="http://localhost:8080")
    p.add_argument("--dataset", default="annapurna", choices=["annapurna", "godavari"])
    p.add_argument("--workspace", default="demo")
    p.add_argument("--handover")
    p.add_argument("--report")
    a = p.parse_args()
    api = a.api.rstrip("/")
    password = os.environ.get("DEMO_PASSWORD", "annapurna-demo")
    r = evaluate(api, a.workspace, password, a.dataset, a.handover)
    text = report(api, r)
    print(text)
    if a.report:
        pathlib.Path(a.report).write_text(text, encoding="utf-8")
        print(f"Report written to {a.report}")
    sys.exit(0 if all(ok for _, ok, _ in r["checks"]) else 1)


if __name__ == "__main__":
    main()
