# Baton — Complete Documentation

**Every handover sentence carries its receipt.**

Baton is a knowledge-handover platform. When someone leaves a project, Baton reads the project's authorised
records (chats, documents, task boards, repositories), reconstructs what the next person needs to know, and links
every statement to the exact quote it came from. Anything without a source becomes a question for the person
leaving. A project lead reviews and approves the result, and each successor sees a frozen, signed version filtered
to what they are allowed to see.

Team Bramhastra: Deven Rikame (lead, backend) · Bhushan Shinde (database, AI prompts) · Shawn Dmello (frontend, deployment)

---

## Contents

1. [Live system](#1-live-system)
2. [Core ideas](#2-core-ideas)
3. [Who uses Baton](#3-who-uses-baton)
4. [Features](#4-features)
5. [How a handover works, step by step](#5-how-a-handover-works-step-by-step)
6. [Architecture](#6-architecture)
7. [Data ownership and multi-tenancy](#7-data-ownership-and-multi-tenancy)
8. [Security and privacy](#8-security-and-privacy)
9. [AI](#9-ai)
10. [Sources and connectors](#10-sources-and-connectors)
11. [Plans and billing](#11-plans-and-billing)
12. [Email](#12-email)
13. [Running locally](#13-running-locally)
14. [Configuration reference](#14-configuration-reference)
15. [Deployment](#15-deployment)
16. [API reference](#16-api-reference)
17. [Database](#17-database)
18. [Frontend](#18-frontend)
19. [Testing](#19-testing)
20. [Demo data](#20-demo-data)
21. [Project structure](#21-project-structure)
22. [Operations runbook](#22-operations-runbook)
23. [Still to set up](#23-still-to-set-up)
24. [Limitations and known gaps](#24-limitations-and-known-gaps)
25. [Glossary](#25-glossary)
26. [AI disclosure](#26-ai-disclosure)

---

## 1. Live system

| Part | Where |
|---|---|
| Web app | https://baton-umber.vercel.app (Vercel) |
| API | https://baton-api-z29j.onrender.com (Render, Docker, free plan) |
| Baton's own database | Neon Postgres (holds only the workspace registry and the demo workspace) |
| Source code | https://github.com/deven065/Baton (`main` is the only branch) |

Every push to `main` deploys both parts automatically: Vercel builds the frontend from its Git connection, and the
GitHub Action [`.github/workflows/deploy-api.yml`](../.github/workflows/deploy-api.yml) calls Render's deploy hook for
the API (it skips pushes that only change the frontend, designs or Markdown).

To try it: open the web app, and on the sign-in page click any of the demo people (workspace `demo`). The free Render
instance sleeps when idle, so the first request after a while can take up to a minute.

---

## 2. Core ideas

- **Every claim has a receipt.** A claim is a single statement ("Priya owns the payments migration"). It is only
  shown as fact if code (not the AI) finds its quote, word for word, in a source the reader can see.
- **Gaps become questions.** Ownerless work, missing evidence and contradictions turn into exit-interview
  questions for the person leaving. Their answers become evidence too.
- **Permission-aware.** Every source, document, passage and claim carries a sensitivity level and optional access
  groups. People only see what their clearance and groups allow — in search, in claims, in the handover, everywhere.
- **The company owns its data.** Each company's handovers live in a PostgreSQL database in the company's own
  account. Baton keeps only the workspace name and an encrypted way to reach that database.
- **Frozen, signed versions.** Recipients never see work in progress, only versions a lead approved, each with a
  SHA-256 fingerprint.

---

## 3. Who uses Baton

| Role | Who | What they do |
|---|---|---|
| **Organisation admin** | Runs the workspace for the company | Adds people and groups, creates projects, reads the audit log, connects tools, sets up AI, manages billing, can disconnect the workspace |
| **Project lead** | Owns a project | Adds sources, starts handovers, reviews claims, resolves conflicts, approves versions, shares them, follows successors' progress, watches knowledge risk |
| **Departing member** | The person leaving | Answers exit-interview questions (their answers become evidence) |
| **Recipient / successor** | The person taking over | Reads the approved handover, works through the checklist, asks questions, acknowledges it |

The web app sends each person to the right place after sign-in: admins and leads to `/app`, the person leaving to
`/interview`, recipients to `/handover`.

**Sensitivity levels** (lowest to highest): `PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, `RESTRICTED`. A person's clearance
is the highest level they may read. **Access groups** (for example `engineering`, `operations`) further restrict a
source to members of those groups.

---

## 4. Features

### Workspaces and administration
- **Self-service sign-up** (`/signup`): company name, workspace name, the company's own database, first admin.
  Baton checks the database (PostgreSQL 14+, pgvector available, empty `public` schema, public host) before using it.
- **People and groups**: add people with a clearance and groups; they get a one-time temporary password and must
  change it on first sign-in (and, with email on, a link to choose their own). Reset passwords, deactivate people.
  Guard rails: you cannot demote or deactivate yourself, and a workspace always keeps one active admin. Groups in
  use by sources or claims cannot be deleted.
- **Projects and teams**: admins create projects with a lead; leads manage their team. A project always has a lead,
  and nobody with an open handover can be removed.
- **Audit log**: every important action (sign-ins, source uploads, reviews, approvals, shares, views, exports,
  setting changes) is recorded in the company's own database and readable by its admins. Filter it by event or
  category (every event type the workspace has recorded, with counts), person, date range and text; load older
  events; download what is shown as CSV.
- **Workspace page**: shows where the data lives; lets an admin **disconnect** the workspace (see §7).

### Handover workflow
- **Sources**: upload chat exports, meeting notes, documents and task-board CSVs, or read live from GitHub, Slack
  and Google Drive. Everything is redacted before it is stored.
- **Reconstruct**: an 8-stage pipeline turns sources into claims, conflicts, a timeline and exit questions, with live
  progress.
- **Review**: accept, edit, reject or ask for more information on each claim, with keyboard shortcuts, full revision
  history and optimistic locking (two people cannot overwrite each other).
- **Conflicts**: side-by-side view of sources that disagree; the lead picks the winner with a reason.
- **Timeline**: decisions and changes in date order.
- **View as**: preview exactly what a given recipient would see.
- **Exit interview**: the person leaving answers questions; answers are redacted, refused if they contain keys or
  passwords, and become `TESTIMONY` evidence.
- **Approval gate**: the server checks the handover before it can be frozen (see §5).
- **Versions and diff**: every approval creates a numbered version with a SHA-256 hash; compare any version with
  the handover as it is now.
- **Share**: share an approved version with team members, each with a view name and an expiry (up to 90 days).
  Track who opened and acknowledged it; revoke at any time.

### For successors
- **Start here**: project, handover, top risks and blockers, what changed since their previous version.
- **Checklist**: Day 1 / Week 1 / Month 1 items generated from the claims (no AI), each linked to its claim.
- **Ownership map**: owners, contacts and access to transfer, with the quote behind each.
- **Timeline**: dated events they are allowed to see.
- **Ask the handover**: ask a question in plain words and get an answer from the approved version only, with the
  claims and quotes behind it. If the handover cannot answer, send the question to the person leaving; their reply
  is added as evidence and shown back.
- **Acknowledge**: confirm the handover landed.

### Insight for leads
- **Knowledge-risk radar** (`/app/risk`): the bus factor (fewest people who wrote half of what the project knows),
  each person's share of the written sources, topics with only one source, access only one person mentions, and
  topics that went quiet for 60 days. Filtered by the viewer's clearance.
- **Living handovers**: the approval page warns when the approved version is out of date (claim changes, new exit
  answers, re-synced sources) and offers to start the next version. After approving it, one click moves every
  recipient to the new version, keeping their view name and expiry; they see what changed.
- **Successors** (`/app/successors`): each recipient's checklist progress by phase with due dates (Day 1 = 1 day,
  Week 1 = 7 days, Month 1 = 30 days after sharing), overdue items, and questions waiting for an answer.
- **Signed export**: download any approved version as a zip (snapshot exactly as hashed, checklist, shares, audit
  trail, printable HTML report, signed manifest). **Verify an export** checks every file and the signature, and
  catches any change.

### Everyday comforts
- **Header search**: leads search claims (opens Review filtered); admins search people. Press `/` to focus it.
- **Notifications bell**: exit answers and recipients opening or acknowledging the handover.
- **Forgot password**: email link (when email is on), otherwise instructions to ask an admin.
- **Keep me signed in**: when unticked, the session ends when the tab closes.
- **Your next step guide**: the dashboard (and the People and Projects pages for admins) shows the whole path from an
  empty workspace to a shared handover as a checklist ticked from real data (team, project, sources, handover,
  reconstruction, review, approval, sharing), with one button for the next step. The sidebar shows the same next step on
  every page.
- **Handover switcher**: leads see the current handover at the top of the sidebar; click it to switch to another
  handover in the project (remembered in this browser) or to **rename** it (recorded as `HANDOVER_RENAMED`).
- **Account menu**: the avatar at the top right shows who you are and opens **Account settings** (profile,
  clearance, groups, change password), **Organisation settings** for admins, and **Sign out**.

### Organisation settings (admins)
| Page | What it does |
|---|---|
| People & groups | People, clearances, groups, temporary passwords |
| Projects | Projects, leads and teams |
| Audit log | Everything that happened in the workspace |
| Connections | The company's own GitHub, Slack and Google Drive credentials |
| AI | The company's own AI provider and key, with usage |
| Billing | Plan, usage and upgrade |
| Workspace | Where the data lives; disconnect |

---

## 5. How a handover works, step by step

1. **An admin creates the workspace and the project**, adds people, and makes someone the project lead.
2. **The lead adds sources** to the project and confirms they are authorised to share each one. Each source is
   fetched, redacted, split into passages, embedded and stored (an *ingest* job).
3. **The lead starts a handover** for the person leaving and picks a scenario:
   *Planned* (they are still around), *Sudden* (they are not; questions go to the lead), or *Split* (several successors).
4. **Reconstruct** runs the pipeline (a *reconstruct* job):

   | Stage | What happens |
   |---|---|
   | `SIGNAL` | Facts straight from systems of record (task board, GitHub), no AI |
   | `EXTRACT` | Redacted passages, wrapped in `<chunk>` tags as untrusted data, are sent to the model in batches grouped by access class |
   | `VERIFY` | Code locates every quoted sentence in its passage; unmatched quotes are dropped |
   | `CONSOLIDATE` | Duplicate claims are merged |
   | `CONFLICT` | Claims that contradict each other are linked |
   | `SCORE` | Status and confidence are computed by code |
   | `GAP` | 6–12 exit-interview questions are written |
   | `TIMELINE` | Dated events are ordered |

   Stages are idempotent and resume after a crash. A claim is visible only to someone who may see **every**
   passage it cites.
5. **The person leaving answers** exit questions. Each answer is stored as testimony and re-scores the claims it
   touches.
6. **The lead reviews** claims (states: `PENDING → ACCEPTED | EDITED | REJECTED | NEEDS_INFO`; rejected and
   needs-info can go back to pending) and resolves conflicts.
7. **Approval gate.** Blocking checks: there are claims to hand over; every verified claim has a matching quote; no
   unresolved conflicts; critical claims reviewed; inferred claims decided. Warnings only: unanswered exit questions,
   claims still pending.
8. **Approve** freezes version *N*: a snapshot of every non-rejected claim with its evidence and the timeline, hashed
   with SHA-256, plus the generated checklist. The handover becomes `APPROVED`.
9. **Share** with successors; the handover becomes `SHARED`. Each recipient sees the frozen version filtered by
   their own clearance and groups; every view is audited.
10. **Successors** read, tick off checklist items, ask questions and acknowledge. The lead watches progress.
11. **If things change** (new answers, edits, re-synced sources), the lead starts version *N+1*, approves it and moves
    the recipients across.

**Handover states:** `DRAFT → RECONSTRUCTING → IN_REVIEW → APPROVED → SHARED`, with `ARCHIVED` at the end. Editing an
approved or shared handover returns it to `IN_REVIEW`; recipients keep their frozen version meanwhile.

**Claim statuses:** `VERIFIED` (quoted in two or more sources), `SINGLE_SOURCE`, `INFERRED` (the model's reasoning,
no quote — never shown as fact), `CONFLICTING`, `MISSING_EVIDENCE`, `STALE`.

**Claim types:** decision, open work, owner, risk, dependency, blocker, recurring issue, contact, open question,
process, access to transfer.

---

## 6. Architecture

```
Browser (React app on Vercel)
   │  HTTPS, JSON, bearer token (JWT)
   ▼
Baton API (Spring Boot on Render)
   ├── TenantFilter: picks the company from the token's signed workspace id
   ├── Controllers → services → JdbcClient / JPA
   ├── Job worker: ingest and reconstruct jobs, polled per active workspace
   ├── Model client: per-workspace router (company's own AI, else offline rules)
   └── Mailer (Resend), payments (Razorpay), connectors (GitHub, Slack, Drive)
   │
   ├──► Baton's home database (Neon): control.tenant registry, payments, demo workspace
   └──► Each company's own PostgreSQL: all of that company's data
```

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 4.1 (Web MVC, Security with OAuth2 resource server/JWT, Data JPA, JdbcClient, Validation, Actuator), Flyway, HikariCP, Jackson 3 |
| Database | PostgreSQL 16 with pgvector (14+ supported for company databases) |
| Embeddings | MiniLM run locally on the server (optional; off on the free Render plan) |
| AI | Any OpenAI-compatible chat API (Groq, OpenAI, OpenRouter, …), or the offline extractor |
| Frontend | React 18, TypeScript, Vite 5, Tailwind CSS 4.1, TanStack Query 5, React Router 6, lucide icons |
| Tests | JUnit 5, Spring Boot Test, MockMvc, Testcontainers (real pgvector) |
| Hosting | Render (API, Docker), Vercel (frontend), Neon (home database), GitHub Actions (deploy hook) |

**Background jobs.** Long work (ingest, reconstruct) runs as jobs stored in each company's database with progress,
heartbeat and retries. The worker only polls workspaces with recent activity. Progress streams to the browser with
server-sent events (`/api/jobs/{id}/events`).

**Errors** always look like `{"error": "CODE", "message": "…", "details": …}`. Codes: `VALIDATION_FAILED`,
`ATTESTATION_REQUIRED` (400) · `UNAUTHENTICATED` (401) · `PLAN_LIMIT` (402) · `FORBIDDEN_ROLE`, `SHARE_INACTIVE`
(403) · `NOT_FOUND` (404 — also used for things you may not see, so their existence is never revealed) ·
`PASSWORD_CHANGE_REQUIRED` (403) · `CLAIM_STALE`, `APPROVAL_BLOCKED`, `INVALID_TRANSITION`, `RUN_IN_PROGRESS`, `WORKSPACE_TAKEN`, `ALREADY_EXISTS`,
`NOT_ALLOWED` (409) · `FILE_TOO_LARGE` (413) · `UNSUPPORTED_TYPE` (415) · `DATABASE_UNREACHABLE`,
`DATABASE_UNSUITABLE` (422) · `TOO_MANY_REQUESTS` (429, with `Retry-After`) · `INTERNAL_ERROR` (500, with a
reference number) · `LLM_UNAVAILABLE` (503).

---

## 7. Data ownership and multi-tenancy

Each company (a **workspace**) brings an empty PostgreSQL database in its own account (Neon, Supabase, AWS RDS,
Cloud SQL, Azure or self-hosted). A public step-by-step guide is at `/connect-database`.

| Stored in the company's database | Stored by Baton |
|---|---|
| People, groups, projects, teams | Workspace name and display name |
| Sources: redacted text, passages, embeddings; originals of redacted values, encrypted | The connection details, encrypted with Baton's master key |
| Claims, evidence, revisions, conflicts, timeline | The workspace's own encryption key, encrypted with the master key |
| Exit questions and answers | Plan end date and payment records (no company content) |
| Approved versions, checklists, shares, progress | |
| Audit log, AI settings and usage, tool credentials (encrypted) | |

How it works:
- `control.tenant` in Baton's home database lists workspaces. Every sign-in token carries the workspace id (signed),
  and a filter routes each request to that company's database through a small connection pool.
- On sign-up and at every start, Baton creates or upgrades the company's tables with Flyway.
- Each workspace has its **own AES-256-GCM key**, so one company's encrypted data cannot be opened with another's key.
- The **demo** workspace lives in Baton's own database and uses the master key; it cannot be disconnected and has no
  plan limits.

**Taking access away:** change or delete the database user's password (instant), or an admin uses
**Organisation → Workspace → Disconnect**: they type the workspace name and their password; Baton writes a
`WORKSPACE_DISCONNECTED` entry in the company's own audit log, deletes its copy of the connection details and the
workspace key, signs everyone out, and shows the key once so the company can still decrypt the original files in
its database. All data stays in the company's database.

---

## 8. Security and privacy

- **Role-based access is enforced by the API, not the web app.** Every endpoint checks the caller on the server;
  the web app's route guards only decide what to show. Role, clearance, groups and active status are read from the
  database on every request (never trusted from the token), so demotion, removal from a project or deactivation
  applies to tokens already issued. Another project's or company's ids return 404. A forged or edited token is
  rejected (401). `RbacMatrixTest` calls every endpoint as an admin, lead, leaver, member, recipient, outsider,
  another company's admin, a forged token and no token, and fails on any request that is not refused.
- **Passwords**: BCrypt hashes in the company's database; at least 12 characters. A temporary password opens only
  the caller's profile and the password change until it is changed (enforced by the API, error
  `PASSWORD_CHANGE_REQUIRED`).
- **Sessions**: signed JWTs carrying the user and workspace; "Keep me signed in" decides between local and session
  storage in the browser.
- **Rate limits**: sign-in 30 attempts per address per 10 minutes and 8 wrong passwords per account per 15 minutes;
  sign-up 5 per address per hour; workspace lookup 60 per address per minute; password-reset requests, AI and
  connection tests, questions and disconnect attempts are limited too. The address used is the last entry Render's
  proxy adds to `X-Forwarded-For`, so a spoofed header does not help.
- **No enumeration**: unknown workspaces get the same answer as a wrong password; forgot-password always answers the
  same way and sends email in the background so timing reveals nothing; hidden things return 404.
- **Redaction before storage and before any AI call**: emails, phone numbers, API keys, passwords and private keys
  become placeholders like `[EMAIL_1]`; originals are kept encrypted. Exit answers containing keys or passwords are
  refused.
- **Prompt-injection guard**: source text is wrapped as untrusted data; passages that try to instruct the model are
  flagged and treated as data.
- **Quotes verified by code**: the model only proposes; code checks every quote and computes status and confidence.
- **Permission filtering everywhere**: search filters before ranking; a claim is hidden if any passage it cites is.
- **Encrypted secrets**: AI keys and tool credentials are encrypted with the workspace key in the company's database
  and never returned by the API (only a short hint).
- **Server-side request safety**: company databases and AI provider addresses must be public hosts (no private,
  loopback or link-local addresses); AI providers must use HTTPS; the Google token address is fixed, never taken from
  a pasted key file.
- **Logs never contain company content**: errors are logged by type, SQL state and code location only; framework
  loggers that could print values or database addresses are silenced.
- **Password-reset links**: one-time, 30 minutes (3 days for new people), stored only as a SHA-256 hash.
- **Payment webhook**: trusted only with a valid HMAC signature, the right amount and currency, and counted once.
- **Signed exports**: manifest signed with a key derived from the workspace key; any changed file is detected.
- **Read-only connectors**: Baton never writes to GitHub, Slack or Drive.
- **Authorisation attestation**: adding a source requires confirming you are allowed to share it.
- **Shared demo is locked against vandalism**: the demo workspace's passwords are public, so there the API refuses
  password changes, deactivating, demoting or resetting people, removing team members or changing their roles, revoking shares,
  deleting groups, and any change to AI, connections, billing or the workspace (`DEMO_LOCKED`, 403). The handover
  workflow and adding people, groups and projects still work. Other workspaces are unaffected.
- **No development secrets in deployment**: against a database that is not on the same machine, the server refuses
  to start with the development `JWT_SECRET` and logs a security error for the development `CRYPTO_KEY`.

---

## 9. AI

Baton works without any AI. AI only proposes claims; code does the rest.

| Mode | When | What happens |
|---|---|---|
| **Offline** (default) | No workspace AI and `AI_MOCK=true` | Replays recorded answers for identical prompts, otherwise a rule-based extractor. Nothing leaves Baton. |
| **Workspace's own AI** | An admin connects a provider under **Organisation → AI** | That company's calls go to its provider, model and account, with their own rate limits and circuit breaker. Usage (calls, tokens, failures) is shown for 30 days. |
| **Server AI** | `AI_MOCK=false` and `GROQ_API_KEY` set on the server | A Baton-wide Groq account for workspaces without their own (not used in production) |

- Supported providers: Groq, OpenAI, OpenRouter, or any OpenAI-compatible chat API (public HTTPS address).
- **Test connection** sends a two-word prompt and no company data.
- Only redacted passages are sent; a test runs a fake provider to prove that placeholders go out and real email
  addresses do not.
- Calls go through: response cache → circuit breaker → concurrency limit → up to 4 attempts with back-off on 429/5xx.
- **Ask the handover** uses the workspace's AI to write answers from the matched claims (JSON reply with citations);
  without AI it answers by keyword search over the claims.

---

## 10. Sources and connectors

| Type | How | What is read |
|---|---|---|
| `CHAT_EXPORT` | Upload a chat export; the format is recognised from the file: **Slack** (export `.zip` or a channel `.json`), **WhatsApp** ("Export chat" `.txt`, or the `.zip` with media; Android and iPhone formats, day- or month-first dates), **Discord** (DiscordChatExporter `.json` or `.csv`, or a `.zip` of them) | Messages, authors, times; one document per channel per day |
| `MEETING_NOTES` | Upload `.md`, `.txt`, `.pdf`, `.docx` | The notes |
| `DOCUMENT` | Upload `.md`, `.txt`, `.pdf`, `.docx` | The document |
| `TASK_CSV` | Upload a Jira, Trello or GitHub Projects CSV export | Tasks, owners, states |
| `GITHUB` (live) | `owner/name` and optional branch | README, Markdown docs (40), issues and pull requests with comments (200), recent commits |
| `SLACK` (live) | Channel name like `#dev`, or its id | The last 90 days (up to 1,000 messages, 30 threads), real names, one document per day |
| `GOOGLE_DRIVE` (live) | Folder link or id | Google Docs and text files (up to 50, 1 MB each) |
| `TESTIMONY` | Created by exit-interview answers | Answers |

Uploads are limited to 25 MB, kept encrypted only until the ingest job finishes, then deleted. Live sources can be
synced again from the Sources page.

**Connecting tools** (Organisation → Connections, admins only; credentials encrypted in the company's database):

- **GitHub**: a fine-grained personal access token with read-only Contents, Issues, Pull requests and Metadata on the
  chosen repositories. Public repositories work without one.
- **Slack**: create an app at api.slack.com/apps, add bot scopes `channels:history`, `channels:read`,
  `groups:history`, `groups:read`, `users:read`, install it, paste the **Bot User OAuth Token** (`xoxb-…`), and
  `/invite` the app to each channel.
- **Google Drive**: create a service account in a Google Cloud project with the Drive API enabled, add a JSON key,
  paste the whole file, and share each folder with the service account's email as **Viewer**.

Each connection has **Test** (checks the credential without reading data) and **Disconnect**.

**Trust tiers** used in scoring: systems of record (GitHub, task boards) > documents, meeting notes, Drive, testimony
> chat (exports and Slack).

---

## 11. Plans and billing

| | Free | Team |
|---|---|---|
| Price | Free | ₹4,999 per month (configurable) |
| Active people | 5 | 100 |
| Projects | 1 | 50 |
| Sources | 10 | 1,000 |
| Features | All | All |

- Limits only stop **adding**; nothing is removed or hidden when a plan lapses. Reaching a limit returns
  `PLAN_LIMIT` (402) with a message pointing to Billing. The demo workspace has no limits.
- **Paying**: Organisation → Billing → Upgrade creates a Razorpay payment link; the admin pays on Razorpay's page
  (Baton never sees card details) and returns to Billing. Razorpay calls Baton's webhook; each paid link adds one
  month (from today or from the current end, whichever is later). No automatic renewal: pay again to extend.
- Online payment stays off until all three Razorpay settings are present; the page then says to contact the team.

---

## 12. Email

Sent through Resend's HTTP API, on a background thread, only when `RESEND_API_KEY` is set:

- Password reset links (30 minutes).
- "You've been added" links for new people to choose their own password (3 days).
- "A handover was shared with you", "An updated handover is ready", and "Someone asked about your handover".

Emails never contain handover content, only names, titles and links. Links use `APP_URL`. Without a verified domain
in Resend, mail is delivered only to the Resend account owner's address; set `MAIL_FROM` to a sender on a verified
domain for real use.

---

## 13. Running locally

**Requirements:** JDK 21, Docker (Docker Desktop on Windows/macOS), Node.js 20+.

```bash
docker compose up -d                     # Postgres 16 + pgvector on localhost:5433 (user/password: baton)
./mvnw spring-boot:run                   # API on http://localhost:8080
```

```bash
cd frontend
npm install
npm run dev                              # web app on http://localhost:5173 (proxies /api to :8080)
```

On first start the demo workspace is created and the synthetic **Project Annapurna** team is seeded (password
`annapurna-demo`, see `baton.demo.password`). Sign in at http://localhost:5173 with workspace `demo`, or click a demo
person.

To register **your own test workspace** against a database on your machine, start the API with
`ALLOW_PRIVATE_DB_HOSTS=true` (private hosts are refused otherwise), create an empty database
(for example `docker exec baton-postgres psql -U baton -c "CREATE DATABASE acme"`), then sign up at `/signup` with
`postgresql://baton:baton@localhost:5433/acme`.

Load the demo sources and reconstruct Priya's handover in one go:

```bash
bash demo/load-demo.sh                   # optional argument: API base URL
```

Build for production:

```bash
./mvnw -DskipTests package               # target/baton-*.jar
cd frontend && npm run build             # frontend/dist
docker build -t baton-api .              # the API image used on Render
```

---

## 14. Configuration reference

All settings are environment variables (defaults in `src/main/resources/application.properties`).

**Core**

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | local Docker | Baton's home database |
| `JWT_SECRET` | dev value | Token signing secret, at least 32 bytes. **Set in production.** |
| `CRYPTO_KEY` | dev value | Master key, base64 of 32 bytes (`openssl rand -base64 32`). **Set in production; never change it** (it unlocks every workspace's key). |
| `CORS_ORIGINS` | `http://localhost:5173` | Allowed web-app origins |
| `APP_URL` | `http://localhost:5173` | Web-app address used in email and payment links |
| `ALLOW_PRIVATE_DB_HOSTS` | `false` | Allow local/private company databases (local development only) |
| `DEMO_SEED` | `true` | Create the demo workspace data |
| `DEMO_PASSWORD` | `annapurna-demo` | Password for every demo person |
| `DEMO_LOCKED` | `true` | Refuse changes in the demo workspace that would break it for the next visitor |
| `EMBEDDINGS_ENABLED` | `true` | Local MiniLM embeddings (needs memory; `false` on the free Render plan → keyword search) |

**AI**

| Variable | Default | Purpose |
|---|---|---|
| `AI_MOCK` | `true` | Offline extractor unless a workspace has its own AI |
| `GROQ_API_KEY` | empty | Optional server-wide Groq key |
| `GITHUB_TOKEN` | empty | Optional server-wide read-only GitHub token (used when a workspace has none) |

**Limits**

| Variable | Default |
|---|---|
| `LOGIN_PER_IP` | 30 per 10 minutes |
| `LOGIN_FAILURES_PER_ACCOUNT` | 8 per 15 minutes |
| `SIGNUP_PER_IP` | 5 per hour |
| `LOOKUP_PER_IP` | 60 per minute |

**Email**

| Variable | Default | Purpose |
|---|---|---|
| `RESEND_API_KEY` | empty | Turns email on |
| `MAIL_FROM` | `Baton <onboarding@resend.dev>` | Sender address |

**Billing**

| Variable | Default | Purpose |
|---|---|---|
| `FREE_PEOPLE`, `FREE_PROJECTS`, `FREE_SOURCES` | 5, 1, 10 | Free plan limits |
| `TEAM_PRICE_MINOR` | `499900` | Team price in the currency's minor unit (paise) |
| `BILLING_CURRENCY` | `INR` | Currency |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` | empty | All three turn online payment on |

**Frontend (Vercel build variables)**

| Variable | Purpose |
|---|---|
| `VITE_API_BASE` | API address, e.g. `https://baton-api-z29j.onrender.com` (empty locally: the Vite proxy is used) |
| `VITE_DEMO_PASSWORD` | Shows the one-click demo people on the sign-in page |

---

## 15. Deployment

**API on Render** (service `baton-api`, Docker, Singapore, free plan)
- Built from the repository's `Dockerfile` (two stages: JDK build, JRE run; memory tuned for 512 MB).
- Environment: `DB_URL`/`DB_USER`/`DB_PASSWORD` (Neon), `JWT_SECRET`, `CRYPTO_KEY`, `CORS_ORIGINS`, `APP_URL`,
  `EMBEDDINGS_ENABLED=false`, `AI_MOCK=true`, plus email and Razorpay values when available.
- Deploys: the GitHub Action calls the deploy hook (secret `RENDER_DEPLOY_HOOK` in the repository) on every push to
  `main` that touches backend files; it can also be run by hand from the Actions tab. Render's own auto-deploy does
  not see pushes since the repository was recreated.
- Health check: `GET /actuator/health`.

**Frontend on Vercel** (project `baton`)
- Root directory `frontend`, framework Vite, `frontend/vercel.json` rewrites every path to `index.html`.
- Connected to the GitHub repository; every push to `main` deploys to https://baton-umber.vercel.app.

**Database on Neon**: Baton's home database (registry, payments, demo workspace). Company data is never here.

---

## 16. API reference

All paths start with `/api`. Everything needs `Authorization: Bearer <token>` except where marked *public*.
"Lead" means lead of that project; "admin" means organisation admin.

### Accounts and sign-in
| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/auth/login` | public | `{workspace?, email, password}` → `{token, expiresAt, user}` |
| GET | `/auth/options` | public | `{passwordResetByEmail}` |
| POST | `/auth/forgot-password` | public | `{workspace, email}` → always 202 |
| POST | `/auth/reset-password` | public | `{workspace, token, newPassword}` → 204 |
| GET | `/me` | anyone | Current user, clearance, groups, workspace |
| POST | `/me/password` | anyone | `{currentPassword, newPassword}` |

### Workspaces
| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/workspaces` | public | Sign-up: `{slug, name, database{url, username?, password?}, admin{displayName, email, password}}` → signs the admin in |
| GET | `/workspaces/{slug}` | public | Display name only |
| GET | `/admin/workspace` | admin | Name, database host/port/name, counts |
| POST | `/admin/workspace/disconnect` | admin | `{confirm, password}` → `{recoveryKey, disconnectedAt}` |

### Administration
| Method | Path | Who | Notes |
|---|---|---|---|
| GET, POST | `/admin/users` | admin | List; create (returns a temporary password and `invitedByEmail`) |
| PATCH | `/admin/users/{id}` | admin | Name, role, clearance, groups, active |
| POST | `/admin/users/{id}/reset-password` | admin | New temporary password |
| GET, POST | `/admin/groups` | admin | |
| DELETE | `/admin/groups/{id}` | admin | Refused while in use |
| GET | `/admin/audit?action=A,B&actor=&from=&to=&q=&before=&limit=` | admin | Event types (comma-separated), person, dates (YYYY-MM-DD, inclusive), text; page with `before` |
| GET | `/admin/audit/actions` | admin | Every event type recorded, with counts |
| GET | `/admin/projects` | admin | |
| POST | `/projects` | admin | `{name, description?, leadUserId}` |
| GET | `/projects/{id}/team` | lead, admin | |
| POST | `/projects/{id}/members` | lead, admin | |
| PATCH, DELETE | `/projects/{id}/members/{userId}` | lead, admin | |
| GET | `/directory` | lead, admin | People to add to teams |

### Projects, sources, jobs, search
| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/projects` · `/projects/{id}` | member | 404 if not a member |
| POST | `/projects/{id}/sources` | member | Multipart: `type`, `title`, `sensitivity`, `aclGroups`, `authorisationAttested=true`, and `file`, or `repo`(+`branch`), `channel`, or `folder` → 202 `{source, jobId}` |
| GET | `/projects/{id}/sources` | member | Sources you may see |
| GET | `/sources/{id}` · `/sources/{id}/documents` | member | 404 if not visible |
| POST | `/sources/{id}/sync` | lead | Live sources only |
| GET | `/jobs/{id}` · `/jobs/{id}/events` | member | Status; server-sent progress events |
| GET | `/chunks/{id}` | visible only | Evidence passage |
| GET | `/projects/{id}/search?q=` | member | Permission-filtered keyword + vector search |
| GET | `/projects/{id}/risk` | lead, admin | Knowledge-risk radar |

### Handovers and review
| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/projects/{id}/handovers` | lead | `{departingUserId, title, scenario, aliases?}` |
| PATCH | `/handovers/{id}` | lead | Rename: `{title}` |
| GET | `/projects/{id}/handovers` · `/handovers/{id}` | lead, leaver | |
| POST | `/handovers/{id}/reconstruct` | lead | 202 `{jobId}` |
| GET | `/handovers/{id}/runs/latest` | lead | |
| GET | `/handovers/{id}/claims?type=&status=&reviewState=&critical=&page=&size=` | lead | |
| POST | `/handovers/{id}/claims` | lead | Add a claim; quotes verified |
| GET, PATCH | `/claims/{id}` | lead | PATCH `{version, reviewState?, title?, body?, attributes?, critical?, reason?}` |
| GET | `/claims/{id}/revisions` | lead | |
| POST | `/claims/{id}/resolve-conflict` | lead | `{otherClaimId, reason}` |
| GET | `/handovers/{id}/gaps` · `/timeline` · `/summary` | lead, leaver | |
| POST | `/gaps/{id}/answer` | leaver | `{answer, sensitivity?, owner?}` |
| POST | `/gaps/{id}/skip` | lead, leaver | `{reason?}` |
| GET | `/handovers/{id}/preview?as={userId}` | lead | View as a recipient |

### Approval, versions, sharing
| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/handovers/{id}/approval` | lead | Gate checks |
| POST | `/handovers/{id}/approve` | lead | 201 version; 409 `APPROVAL_BLOCKED` |
| GET | `/handovers/{id}/versions` | lead | |
| GET | `/handovers/{id}/diff?from=&to=` | lead | `to=0` = now |
| GET | `/handovers/{id}/drift` | lead | Changes since the approved version |
| POST | `/handovers/{id}/reopen` | lead | Start the next version |
| GET, POST | `/handovers/{id}/shares` | lead | POST `{recipientId, roleLabel?, expiresAt?}` |
| POST | `/handovers/{id}/shares/move-to-latest` | lead | `{moved}` |
| POST | `/shares/{id}/revoke` | lead | |
| GET | `/handovers/{id}/progress` | lead | Successor progress |
| GET | `/handovers/{id}/versions/{n}/export` | lead | Signed zip |
| POST | `/exports/verify` | lead | Multipart `file` → `{valid, signatureOk, filesOk, matchesRecord, problems}` |

### Recipients
| Method | Path | Notes |
|---|---|---|
| GET | `/shares` | My active shares |
| GET | `/shares/{id}` | The frozen version, filtered; checklist; what changed |
| PUT | `/shares/{id}/checklist/{itemId}` | `{done}` |
| POST | `/shares/{id}/acknowledge` | |
| POST | `/shares/{id}/ask` | `{question}` → `{found, answer, mode, citations}` |
| GET, POST | `/shares/{id}/questions` | Questions sent to the person leaving |

### Settings
| Method | Path | Who | Notes |
|---|---|---|---|
| GET, PUT, DELETE | `/admin/ai` | admin | PUT `{provider, baseUrl?, model?, apiKey?, enabled?}` |
| POST | `/admin/ai/test` | admin | |
| GET | `/integrations` | anyone | Which tools are connected (no secrets) |
| PUT, DELETE | `/admin/integrations/{GITHUB\|SLACK\|GOOGLE_DRIVE}` | admin | PUT `{secret}` |
| POST | `/admin/integrations/{kind}/test` | admin | `{secret?}` |
| GET | `/admin/billing` | admin | Plan, limits, usage |
| POST | `/admin/billing/checkout` | admin | `{url}` of the Razorpay payment page |
| POST | `/billing/razorpay/webhook` | public, signed | `X-Razorpay-Signature` |
| GET | `/actuator/health` (no `/api`) | public | |

---

## 17. Database

**Company database migrations** (`src/main/resources/db/migration`, applied to every workspace):

| Version | Adds |
|---|---|
| V1 | People, groups, projects, sources, documents, passages (pgvector), claims, evidence, links, revisions, exit questions, versions, checklists, shares, progress, jobs, append-only audit log |
| V2 | Encrypted staging for uploaded files |
| V3 | Reconstruction: response cache, timeline, `SHARED` status |
| V4 | Must-change-password flag |
| V5 | One-time password links |
| V6 | AI settings and usage |
| V7 | Successor questions, exact hashed snapshot text, previous version on shares |
| V8 | Slack and Google Drive source types; tool credentials |

**Baton's control schema** (`src/main/resources/db/control`, home database only):

| Version | Adds |
|---|---|
| V1 | `control.tenant`: workspaces, encrypted connection details and keys |
| V2 | Plan end date; `control.payment` |

---

## 18. Frontend

`frontend/` is a single-page React app. Main routes:

| Route | Page |
|---|---|
| `/login`, `/signup`, `/forgot-password`, `/reset-password`, `/change-password` | Accounts |
| `/connect-database` | Public guide to bringing your own database |
| `/disconnected` | Shown once after disconnecting, with the workspace key |
| `/app` | Lead dashboard |
| `/app/sources`, `/reconstruct`, `/review`, `/conflicts`, `/timeline`, `/view-as`, `/approval`, `/share`, `/successors` | Handover workflow |
| `/app/team`, `/app/risk` | Team; knowledge-risk radar |
| `/app/admin/users`, `/projects`, `/audit`, `/connections`, `/ai`, `/billing`, `/workspace` | Organisation settings |
| `/interview` | Exit interview for the person leaving |
| `/handover`, `/handover/ask`, `/checklist`, `/ownership`, `/timeline` | Successor's handover |

Data comes from the API through hooks in `src/data/live.ts` (TanStack Query); `src/lib/http.ts` adds the token,
turns errors into `ApiError`, streams job events and downloads exports; `src/lib/auth.tsx` holds the session and
decides each person's role. The Figma plugin that draws the product design (file "Baton — Complete Product UI/UX") is in `design/figma-plugin/`.

---

## 19. Testing

```bash
./mvnw test          # 107 tests; needs Docker (Testcontainers starts a real pgvector Postgres)
cd frontend && npx tsc -b --noEmit && npm run build
```

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs both on every push and pull request, and adds the
evaluation report to the run's summary page.

### Evaluation against the planted facts

`demo/annapurna/planted-facts.json` lists the **25 facts** planted across the seven demo sources, plus trust checks
(injected instructions, five planted secrets, restricted facts). A fact is found when a claim of the expected type
contains its key words, or for gaps, an exit question does.

- **On every build:** `PlantedFactsEvaluationTest` reconstructs all seven sources in offline mode, scores them,
  writes `target/evaluation-report.md` and fails if recall drops below the floor or any trust check fails.
- **Against any running server**, for example with a real model:

  ```bash
  bash demo/load-demo.sh http://localhost:8080
  python demo/evaluate.py http://localhost:8080 --report demo/evaluation-report.md
  ```

Latest result, offline rules ([`demo/evaluation-report.md`](../demo/evaluation-report.md)): **23 of 25 (92%)**, all
checks pass. The two misses are plain statements with no signal word for the rules ("secrets live in the Render
dashboard", "renewal is due on 2026-12-01"); a model is expected to find them.

| Area | Tests |
|---|---|
| Evidence | `QuoteMatcherTest`, `ClaimScorerTest` |
| Ingest | `RedactorTest`, `ChunkerTest`, `ConnectorTest`, `SourceIngestApiTest` |
| Identity and access | `IdentityApiTest`, `AccessPolicyTest`, `PasswordResetApiTest`, `RbacMatrixTest` (every endpoint × every role) |
| Reconstruction and review | `OfflineExtractorTest`, `ReconstructApiTest`, `ReviewApiTest`, `ConflictDecisionTest` (a conflict decision survives re-runs) |
| Workspaces and admin | `WorkspaceApiTest`, `DatabaseUrlTest`, `CompanyAdminApiTest`, `HardeningApiTest`, `SafeLogTest` |
| Handover lifecycle | `HandoverStateMachineTest`, `LifecycleApiTest`, `HandoverFeaturesApiTest` |
| AI, connectors, billing | `AiSettingsApiTest`, `ConnectorsAndBillingApiTest` (fake AI, Slack, Google and Razorpay servers) |
| Evaluation | `PlantedFactsEvaluationTest` (25 planted facts, secrets, injection, restricted facts) |
| Deployment safety | `DemoGuardTest` (shared demo cannot be vandalised), `SecretsCheckTest` |

Tests cover, among others: workspace isolation, redaction before AI, quote verification, permission filtering,
approval blocking, signed-export tampering, rate limits, disconnect, password links, plan limits and payment
webhook signatures.

---

## 20. Demo data

Everything in the demo is synthetic. **Project Annapurna** is a made-up campus canteen pre-order app built by a
college tech club ("Northfield Tech Club"). Its seven sources are in `demo/annapurna/` (weekly-sync notes, a Slack
channel export, a WhatsApp group export, a Discord channel export, a task CSV, architecture notes, a vendor
contract) with 25 planted facts, a conflict (launch date), gaps and a fake API key that must be redacted. See
`demo/annapurna/README.md` and the evaluation in section 19.

| Person | Email | Clearance | Groups | Role |
|---|---|---|---|---|
| Neha Kulkarni | neha@annapurna.test | RESTRICTED | engineering, operations | Project lead |
| Priya Sharma | priya@annapurna.test | CONFIDENTIAL | engineering | Leaving |
| Arjun Mehta | arjun@annapurna.test | CONFIDENTIAL | engineering | Incoming engineer |
| Rahul Desai | rahul@annapurna.test | INTERNAL | design | Incoming designer |
| Org Admin | admin@annapurna.test | INTERNAL | — | Organisation admin |

---

## 21. Project structure

```
.github/workflows/ci.yml           Backend tests, evaluation and frontend build on every push
.github/workflows/deploy-api.yml   Deploys the API to Render on push
Dockerfile, docker-compose.yml     API image; local Postgres + pgvector
demo/                              Synthetic demo sources and loader script
design/figma-plugin/               Figma plugin that draws the product design
docs/DOCUMENTATION.md              This document
frontend/                          React web app
src/main/java/com/bramhastra/baton/
  admin/         People, groups, audit, workspace settings, disconnect
  ai/            Model clients, per-workspace AI routing, AI settings
  audit/         Audit log
  billing/       Plans, limits, Razorpay
  common/        Errors, rate limiter, safe logging
  config/        Spring configuration
  evidence/      Quote matching and claim scoring
  handover/      Handovers, approval, versions, shares, ask, drift, progress, export
  identity/      Sign-in, users, projects, password reset, demo seed
  insight/       Knowledge-risk radar
  integration/   Tool credentials (GitHub, Slack, Google Drive)
  job/           Background jobs and worker
  mail/          Email through Resend
  reconstruct/   Reconstruction pipeline and stages
  review/        Claim review and exit interview
  security/      JWT, access policy, encryption
  source/        Sources, ingest, redaction, chunking, embeddings, search, connectors
  tenant/        Workspaces, routing to each company's database, master key
src/main/resources/
  application.properties, db/migration, db/control, prompts/
src/test/                          Tests
```

---

## 22. Operations runbook

| Situation | What to do |
|---|---|
| Deploy a change | Push to `main`. Check the "Deploy API" run in GitHub Actions and the Vercel dashboard. |
| API deploy did not start | GitHub → Actions → Deploy API → Run workflow, or Render → baton-api → Manual Deploy. |
| First request is slow | The free Render instance was asleep; it wakes in under a minute. |
| A user reports "Unexpected error (ref abc12345)" | Search Render logs for `ref=abc12345`; the line shows the error type and code location, never content. |
| Someone is locked out after wrong passwords | Wait 15 minutes, or an admin resets their password. |
| A workspace's database is unreachable | That company's requests fail; other workspaces are unaffected. Ask them to check the database and its password. |
| A company wants to leave | Their admin disconnects under Organisation → Workspace; their data stays with them. |
| Rotate `JWT_SECRET` | Everyone is signed out; nothing else changes. |
| `CRYPTO_KEY` | Never change it without re-sealing every workspace's key; losing it locks every workspace. Keep a backup. |
| A payment was made but the plan did not change | Check Razorpay's webhook deliveries; the webhook is idempotent, so redelivering is safe. |

---

## 23. Still to set up

These need accounts or decisions from the owner; each feature stays safely off until then.

| Item | Where | Effect |
|---|---|---|
| `RESEND_API_KEY` (+ `MAIL_FROM` on a verified domain) | Render environment | Turns email on |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, and a webhook to `/api/billing/razorpay/webhook` for `payment_link.paid` | Render environment; Razorpay dashboard | Turns online payment on |
| Team price and Free limits | Render environment | Defaults ₹4,999, 5 people / 1 project / 10 sources |
| An AI key (Groq, OpenAI, …) | In the app: Organisation → AI | Real AI extraction and answers |
| Slack bot token, Google service-account key, GitHub token | In the app: Organisation → Connections | Live Slack, Drive and private GitHub sources |

---

## 24. Limitations and known gaps

- Rate limits and the per-workspace AI clients live in memory, which is fine for a single API instance; several
  instances would need a shared store.
- Payments are one month at a time (no automatic renewal or invoices yet).
- Connectors use pasted credentials rather than "Sign in with Slack/Google" buttons (those need registered apps).
- Slack reads the last 90 days of one channel per source; Drive reads Google Docs and text files, not PDFs or Sheets.
- Embeddings are off on the free Render plan (512 MB), so production search is keyword-only.
- The radar's numbers depend on sources naming their authors; the demo data names few.
- Versions approved before snapshot text was stored (V7) export the normalised JSON, marked as not byte-exact.
- Render's own auto-deploy does not see pushes since the repository was recreated; the GitHub Action replaces it.

---

## 25. Glossary

| Term | Meaning |
|---|---|
| Workspace | One company in Baton, with its own database |
| Source | Something Baton reads: an upload or a live connection |
| Document / passage (chunk) | A source is split into documents, and documents into passages |
| Claim | One statement about the project, with a type, status and evidence |
| Evidence | A quote from a passage that supports a claim |
| Gap / exit question | Something the sources cannot answer, asked of the person leaving |
| Testimony | An exit-interview answer stored as evidence |
| Version | A frozen, hashed snapshot of an approved handover |
| Share | One recipient's access to one version, with an expiry |
| Drift | Changes since the approved version |
| Bus factor | The fewest people who wrote half of what a project knows |

---

## 26. AI disclosure

AI coding assistants were used to generate parts of the code from the team's own specification; the team designed,
reviewed and tested it. Inside the product, AI models run only when a workspace connects its own provider (or the
server is configured with one), and only on redacted text.
