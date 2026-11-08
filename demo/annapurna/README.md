# Project Annapurna (synthetic demo data)

A fictional college tech club building a canteen pre-order app. Everything here is invented: people,
emails (`.example` domains), phone numbers and keys. Priya Sharma, the tech lead, is graduating.

| File | Source type | Sensitivity | Groups | What it plants |
|---|---|---|---|---|
| `2026-10-08-weekly-sync.md` | MEETING_NOTES | INTERNAL | – | Launch on **15 November**, payments owner, webhook risk, vendor dependency |
| `dev.json` (Slack `#dev`) | CHAT_EXPORT | INTERNAL | – | Launch **moved to Nov 29** (conflict), recurring cache issue, Render account on a personal Gmail (access), a prompt-injection message, a blocker |
| `tasks.csv` | TASK_CSV | INTERNAL | – | ANN-12 assigned to Priya (open work), ANN-14 with no assignee (gap), a done task (ignored) |
| `architecture.md` | DOCUMENT | CONFIDENTIAL | engineering | Postgres-over-Firebase decision with reason |
| `vendor-contract.md` | DOCUMENT | RESTRICTED | operations | Vendor contact and renewal decision; only Neha may see it |

Expected after reconstruction (offline mode):

- The two launch decisions are linked as **CONFLICTING**, and the first exit question asks Priya which date is current.
- ANN-12 is **VERIFIED** open work (task board is a system of record); exit questions ask who takes over ANN-12 and who owns ANN-14.
- The Render account is a critical ACCESS claim with a transfer question.
- The injection message is reported as a warning and never changes any status.
- Priya (CONFIDENTIAL, engineering) cannot see anything derived from the vendor contract; Neha can.

Load it into a running backend with `demo/load-demo.sh`.

Extending this toward the 25 planted facts for the evaluation script is the team's Round 2 task.
