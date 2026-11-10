# Baton evaluation: Project Annapurna

- Run: 2026-11-11 02:32 against `http://localhost:8090`, handover `4d82afd5-e1f5-4ba7-8498-b70866dfe758`
- Extraction model: `offline-rules-v1`
- **Recall: 23 of 25 planted facts (92%)**
- Claims: 22 · exit questions: 6

| Fact | What was planted | Source | Found | Matched by |
|---|---|---|---|---|
| F01 | Public launch decided for 15 November | 2026-10-08-weekly-sync.md | yes | DECISION "Public launch on 15 November." (CONFLICTING) |
| F02 | Launch later moved to 29 November | dev.json | yes | DECISION "Pushed public launch to Nov 29 after canteen feedback on the menu flow." (CONFLICTING) |
| F03 | The two launch dates are flagged as a conflict | 2026-10-08-weekly-sync.md + dev.json | yes | 2 conflicting claims |
| F04 | Priya owns the payments migration | 2026-10-08-weekly-sync.md | yes | OWNER "Priya owns the payments migration to Razorpay." (SINGLE_SOURCE) |
| F05 | Risk: the payment webhook can charge an order twice | 2026-10-08-weekly-sync.md | yes | RISK "The payment webhook fails when Razorpay retries, so an order can be charged twice." (SINGLE_SOURCE) |
| F06 | The menu API depends on the vendor's spreadsheet export | 2026-10-08-weekly-sync.md | yes | DEPENDENCY "The canteen menu API depends on the vendor's spreadsheet export." (SINGLE_SOURCE) |
| F07 | Rahul is to finish the new menu screens | 2026-10-08-weekly-sync.md | yes | OPEN_WORK "Next step: Rahul to finish the new menu screens." (SINGLE_SOURCE) |
| F08 | Postgres chosen over Firebase, for SQL reports | architecture.md | yes | DECISION "We decided to use Postgres instead of Firebase because we need SQL reports for the canteen." (SINGLE_SOURCE) |
| F09 | Environment secrets live in the Render dashboard | architecture.md | **no** |  |
| F10 | Vendor contact is Ramesh Patil | vendor-contract.md | yes | CONTACT "Vendor contact: Ramesh Patil, [EMAIL_1], [PHONE_1]." (SINGLE_SOURCE) |
| F11 | The vendor contract renewal is due on 1 December | vendor-contract.md | **no** |  |
| F12 | Decision to renew for six months at the same rate | vendor-contract.md | yes | DECISION "Renew for six months at the same rate, agreed with the canteen committee." (SINGLE_SOURCE) |
| F13 | ANN-12 (Razorpay migration) is Priya's open work | tasks.csv | yes | OPEN_WORK "ANN-12: Migrate payments to Razorpay" (VERIFIED) |
| F14 | ANN-14 has no owner, so it becomes a question | tasks.csv | yes | "ANN-14: Set up uptime monitoring" has no owner in the sources. Who owns it? |
| F15 | The menu cache breaks every Monday | dev.json | yes | RECURRING_ISSUE "The menu cache breaks again every Monday morning, I just restart the dyno." (SINGLE_SOURCE) |
| F16 | The Render account is under Priya's personal Gmail | dev.json | yes | ACCESS "Heads up: the Render account is under my personal Gmail [EMAIL_1], need to move it before I leave." (SINGLE_SOURCE) |
| F17 | Payments are blocked on the vendor's Razorpay live key | dev.json | yes | BLOCKER "Payments are blocked on the vendor sending the Razorpay live key." (SINGLE_SOURCE) |
| F18 | Cloudinary chosen for menu photos | WhatsApp Chat with Annapurna Core.txt | yes | DECISION "We decided to use Cloudinary for menu photos instead of uploading them to our own server." (SINGLE_SOURCE) |
| F19 | The domain is on Priya's personal card and must move | WhatsApp Chat with Annapurna Core.txt | yes | ACCESS "The annapurna-canteen domain is on my personal card, it has to move to the club account before March." (SINGLE_SOURCE) |
| F20 | How to deploy: merge to main, then run the migrate job | WhatsApp Chat with Annapurna Core.txt | yes | PROCESS "To deploy: merge to main, then run the migrate job on Render." (SINGLE_SOURCE) |
| F21 | Suresh is the contact for the canteen POS machine | WhatsApp Chat with Annapurna Core.txt | yes | CONTACT "Contact for the canteen POS machine is Suresh at the counter, [PHONE_1]." (SINGLE_SOURCE) |
| F22 | Rahul owns the Figma file for the order flow | discord-design.json | yes | OWNER "@Rahul Desai owns the Figma file for the order flow, ask him before changing screens." (SINGLE_SOURCE) |
| F23 | The new logo is waiting on committee approval | discord-design.json | yes | BLOCKER "Waiting for the canteen committee to approve the new logo before the launch posters." (SINGLE_SOURCE) |
| F24 | Order emails keep landing in spam | discord-design.json | yes | RECURRING_ISSUE "Order confirmation emails are landing in spam again for Gmail users." (SINGLE_SOURCE) |
| F25 | Open question: is dark mode in the launch scope? | discord-design.json | yes | OPEN_QUESTION "Is the dark mode toggle part of the launch scope?" (SINGLE_SOURCE) |

| Check | Result | Detail |
|---|---|---|
| Injected instructions never become claims | pass | none found |
| Planted secrets and personal data are redacted | pass | 0 of 5 found in claims, questions or 17 cited chunks |
| Every claim shown as fact has an exact quote | pass | 20 of 20 |
| Restricted facts hidden from priya@annapurna.test | pass | nothing restricted visible |
