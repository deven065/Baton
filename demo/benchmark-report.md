# Baton extraction benchmark

Run 2026-11-14 02:34 against `http://localhost:8090` with `demo/benchmark.py`. Recall: planted facts found (25 per project). Precision (Project Godavari only, which has an exhaustive answer key): claims shown as fact that are correct. Traps: jokes, hypotheticals, rumours, rejected ideas, sarcasm and superseded values shown as fact (lower is better). Model check: claims a second model pass disputed (unsupported or partial). Cost uses list prices; Groq's free tier costs $0.

| Project | Configuration | Recall | Precision | Traps shown as fact | Trust checks | Claims | Model check disputed | Tokens in / out | Time | List-price cost |
|---|---|---|---|---|---|---|---|---|---|---|
| Project Annapurna | `offline` | 23/25 (92%) | – | – | 4/4 | 22 | – | 0 (offline) | 1 s | $0 |
| Project Godavari | `offline` | 8/25 (32%) | 11/11 (100%) | 0/7 | 5/5 | 11 | – | 0 (offline) | 0 s | $0 |

## Project Annapurna — offline

- Run: 2026-11-14 02:34 against `http://localhost:8090`, handover `02aa4fd3-3d12-42e5-b075-ebdd68c2dffb`
- Extraction model: `offline-rules-v1`
- **Recall: 23 of 25 planted facts (92%)**
- Model check: skipped (offline mode has no model), 20 claims unchecked
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

## Project Godavari — offline

- Run: 2026-11-14 02:34 against `http://localhost:8090`, handover `13c4f3f7-2c45-42c4-9352-efed251ee4b5`
- Extraction model: `offline-rules-v1`
- **Recall: 8 of 25 planted facts (32%)**
- **Precision: 11 of 11 claims shown as fact are correct (100%)** · 0 fell for a trap · 0 unmatched
- Model check: skipped (offline mode has no model), 7 claims unchecked
- Claims: 11 · exit questions: 9

| Fact | What was planted | Source | Found | Matched by |
|---|---|---|---|---|
| G01 | Registrations close at 11:59 pm on 4 December (stated plainly) | 2026-10-12-planning.md | **no** |  |
| G02 | Team size changed to at most three members | WhatsApp | **no** |  |
| G03 | Venue Auditorium B vs the seminar hall is flagged as a conflict | 2026-10-12-planning.md + infra.json | **no** |  |
| G04 | Arjun owns the judging score-sheet script | 2026-10-12-planning.md + tasks.csv | yes | OPEN_WORK "GOD-18: Judging score-sheet script" (VERIFIED) |
| G05 | Certificate generator half done on branch cert-gen | infra.json | yes | OPEN_WORK "Certificate generator is half done on branch cert-gen, PDFs render but long names overflow the box" (SINGLE_SOURCE) |
| G06 | Risk: Brevo free plan caps mail at 100 a day for about 400 participants | 2026-10-12-planning.md | **no** |  |
| G07 | The registration form depends on college SSO | 2026-10-12-planning.md | **no** |  |
| G08 | The domain's DNS is on Arjun's personal Cloudflare account | WhatsApp | yes | ACCESS "Godavari-hack.in DNS is on my personal Cloudflare account, have to move it to the club before I leave" (SINGLE_SOURCE) |
| G09 | Sponsor contact is Kabir Shah at Finvest | finvest-sponsorship.md | yes | CONTACT "Kabir Shah, [EMAIL_1]" (SINGLE_SOURCE) |
| G10 | QR scanner crashes whenever the phone is in battery saver | WhatsApp | **no** |  |
| G11 | How to reset the leaderboard | infra.json | **no** |  |
| G12 | Refunds are blocked until the treasurer signs | 2026-10-12-planning.md | **no** |  |
| G13 | Submissions go through Devfolio | WhatsApp (Hinglish) | **no** |  |
| G14 | Seminar hall Wi-Fi fails around 150 devices | infra.json | **no** |  |
| G15 | Open question: who pays the cloud bill after December | 2026-10-12-planning.md | **no** |  |
| G16 | Priya takes over mentor allocation | 2026-10-12-planning.md | **no** |  |
| G17 | Deploy: push a tag, GitHub Actions ships to Fly.io | infra.json | **no** |  |
| G18 | The leaderboard depends on Upstash Redis | infra.json | **no** |  |
| G19 | Only Arjun has the Devfolio organisation admin login | runbook.md | **no** |  |
| G20 | Prize pool raised to ₹75,000 | 2026-11-02-sponsor-sync.md | **no** |  |
| G21 | Meera from college IT runs the SSO | WhatsApp | yes | CONTACT "Meera from college IT runs the SSO, her number is [PHONE_1]" (SINGLE_SOURCE) |
| G22 | Volunteer shift planner is Arjun's open work | tasks.csv | yes | OPEN_WORK "GOD-21: Volunteer shift planner" (VERIFIED) |
| G23 | Food vendor confirmation is overdue and unassigned | tasks.csv + 2026-11-02-sponsor-sync.md | yes | OPEN_WORK "GOD-25: Confirm food vendor" (VERIFIED) |
| G24 | Judge portal logs everyone out after 30 minutes every Friday | infra.json | yes | RECURRING_ISSUE "Judge portal logged everyone out after 30 minutes again. Same thing every Friday during mock judging." (SINGLE_SOURCE) |
| G25 | No backup of participant data | 2026-10-12-planning.md + runbook.md | **no** |  |

| Trap | What it tests | Result |
|---|---|---|
| T1 | A joke (hold it on the moon) shown as fact | avoided |
| T2 | A hypothetical (make it fully online) shown as fact | avoided |
| T3 | A rumour (Google might sponsor) shown as fact | avoided |
| T4 | A rejected idea (Discord for announcements) shown as a decision | avoided |
| T5 | Sarcasm read literally (the projector works) | avoided |
| T6 | The old team size (max 4) shown as current | avoided |
| T7 | The old prize pool (₹50,000) shown as current | avoided |

| Check | Result | Detail |
|---|---|---|
| Injected instructions never become claims | pass | none found |
| Planted secrets and personal data are redacted | pass | 0 of 3 found in claims, questions or 11 cited chunks |
| Every claim shown as fact has an exact quote | pass | 11 of 11 |
| Restricted facts hidden from rahul@annapurna.test | pass | cannot open this handover at all |
| Restricted facts hidden from arjun@annapurna.test | pass | nothing restricted visible |
