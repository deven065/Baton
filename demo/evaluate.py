#!/usr/bin/env python3
"""
Scores a reconstructed Project Annapurna handover against the 25 planted facts in
demo/annapurna/planted-facts.json, and checks the trust rules: no injected text, no raw secrets,
nothing restricted shown to the person leaving.

Usage:
    bash demo/load-demo.sh                      # load the demo sources and reconstruct
    python demo/evaluate.py                     # score the newest handover
    python demo/evaluate.py https://baton-api-z29j.onrender.com --report demo/evaluation-report.md

Options: --handover ID, --workspace demo, --report PATH. The demo password comes from DEMO_PASSWORD
(default annapurna-demo). Needs only the Python standard library. Exit code 1 if a check fails.
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
FACTS = HERE / "annapurna" / "planted-facts.json"


def call(api, path, token=None, body=None):
    req = urllib.request.Request(api + path, method="POST" if body is not None else "GET")
    req.add_header("Accept", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, data, timeout=90) as res:
            text = res.read().decode()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        sys.exit(f"{req.method} {path} failed: HTTP {e.code} {e.read().decode()[:200]}")


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
    parts += [str(v) for v in (c.get("attributes") or {}).values() if v is not None]
    return " ".join(parts).lower()


def has_all(text, words):
    return all(w.lower() in text for w in words)


def score(spec, claims, gaps):
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


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("api", nargs="?", default="http://localhost:8080")
    p.add_argument("--workspace", default="demo")
    p.add_argument("--handover")
    p.add_argument("--report")
    a = p.parse_args()
    api = a.api.rstrip("/")
    password = os.environ.get("DEMO_PASSWORD", "annapurna-demo")
    spec = json.loads(FACTS.read_text(encoding="utf-8"))

    lead = login(api, a.workspace, "neha@annapurna.test", password)
    handover = a.handover
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
    results = score(spec, claims, gaps)
    found = sum(1 for _, ok, _ in results if ok)

    # Trust checks.
    checks = []
    everything = json.dumps(claims) + json.dumps(gaps)
    injected = [t for t in spec["checks"]["neverInClaims"] if t.lower() in everything.lower()]
    checks.append(("Injected instructions never become claims", not injected, ", ".join(injected) or "none found"))

    chunk_ids = {e["chunkId"] for c in claims for e in c.get("evidence", []) if e.get("chunkId")}
    stored = everything + "".join(json.dumps(call(api, f"/api/chunks/{cid}", lead)) for cid in chunk_ids)
    leaked = [s for s in spec["checks"]["secretsNeverStored"] if s.lower() in stored.lower()]
    checks.append(("Planted secrets and personal data are redacted", not leaked,
                   f"{len(leaked)} of {len(spec['checks']['secretsNeverStored'])} found in stored text" if leaked
                   else f"0 of {len(spec['checks']['secretsNeverStored'])} found in claims, questions or {len(chunk_ids)} cited chunks"))

    bad_quotes = sum(1 for c in claims if c["status"] in ("VERIFIED", "SINGLE_SOURCE")
                     and not any(e.get("matchScore", 0) >= 0.999 for e in c.get("evidence", [])))
    shown = sum(1 for c in claims if c["status"] in ("VERIFIED", "SINGLE_SOURCE"))
    checks.append(("Every claim shown as fact has an exact quote", bad_quotes == 0, f"{shown - bad_quotes} of {shown}"))

    for email, words in spec["checks"]["hiddenFrom"].items():
        token = login(api, a.workspace, email, password)
        seen = json.dumps(all_claims(api, token, handover)) + json.dumps(call(api, f"/api/handovers/{handover}/gaps", token))
        leaks = [w for w in words if w.lower() in seen.lower()]
        checks.append((f"Restricted facts hidden from {email}", not leaks, ", ".join(leaks) or "nothing restricted visible"))

    model = run.get("model") or "unknown"
    lines = [
        "# Baton evaluation: Project Annapurna",
        "",
        f"- Run: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M')} against `{api}`, handover `{handover}`",
        f"- Extraction model: `{model}`",
        f"- **Recall: {found} of {len(results)} planted facts ({100 * found // len(results)}%)**",
        f"- Claims: {len(claims)} · exit questions: {len(gaps)}",
        "",
        "| Fact | What was planted | Source | Found | Matched by |",
        "|---|---|---|---|---|",
    ]
    for f, ok, hit in results:
        lines.append(f'| {f["id"]} | {f["what"]} | {f["source"]} | {"yes" if ok else "**no**"} | {(hit or "").replace("|", "/")} |')
    lines += ["", "| Check | Result | Detail |", "|---|---|---|"]
    for name, ok, detail in checks:
        lines.append(f'| {name} | {"pass" if ok else "**FAIL**"} | {detail} |')
    report = "\n".join(lines) + "\n"
    print(report)
    if a.report:
        pathlib.Path(a.report).write_text(report, encoding="utf-8")
        print(f"Report written to {a.report}")
    sys.exit(0 if all(ok for _, ok, _ in checks) else 1)


if __name__ == "__main__":
    main()
