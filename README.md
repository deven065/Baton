# Baton

**Every handover sentence carries its receipt.**

When someone leaves a project, their knowledge usually leaves with them. Baton reads the project's authorised
records (chats, documents, task boards, repositories), reconstructs what the next person needs to know, and links
every statement to the exact quote it came from. Anything without a source becomes a question for the person
leaving. A lead reviews and approves it, and each successor gets a frozen, signed version filtered to what they are
allowed to see.

Each company's data stays in **its own database**. Baton keeps only the workspace name and an encrypted way in.

Team Bramhastra: Deven Rikame (lead, backend) · Bhushan Shinde (database, AI prompts) · Shawn Dmello (frontend, deployment)

**Live:** https://baton-umber.vercel.app (pick a demo person on the sign-in page; workspace `demo`)

**Full documentation:** [docs/DOCUMENTATION.md](docs/DOCUMENTATION.md)

---

## What it does

| For | Features |
|---|---|
| **Companies** | Self-service sign-up with your own PostgreSQL · people, groups, clearances · projects and teams · audit log · connect GitHub, Slack and Google Drive with your own credentials · bring your own AI key · Free and Team plans · disconnect any time and keep everything |
| **Project leads** | Add sources (including Slack, WhatsApp and Discord chat exports, and **meeting recordings**: transcribed with timestamps, and every claim plays the moment it was said) · reconstruct a handover with live progress · review claims with full history · resolve conflicts · timeline · view as a recipient · approval gate · versions and diff · share with expiry and revoke · successor progress · knowledge-risk radar · living handovers (drift, new version, move recipients) · signed export and verify |
| **The person leaving** | Exit interview: answers become evidence |
| **Successors** | Start here · Day 1 / Week 1 / Month 1 checklist · ownership map · timeline · **Ask the handover** with cited answers · send unanswered questions to the person leaving · what changed since their last version · acknowledge |

How a handover flows: **sources → redact → reconstruct → review → approve (SHA-256 version) → share → successors read, ask and acknowledge.**

Trust rules the code enforces, not the AI:
- A claim is shown as fact only if its quote is found word for word in a source the reader may see.
- Secrets and personal data are redacted before anything is stored or sent to a model.
- Permissions are checked by the API on every request (not just hidden in the UI); hidden things return 404, and
  role changes apply instantly, even to people already signed in.
- Approval is blocked while conflicts, unreviewed critical claims or undecided inferred claims remain.

## Tech stack

**Backend:** Java 21, Spring Boot 4.1, PostgreSQL 16 + pgvector, Flyway, Testcontainers ·
**Frontend:** React 18, TypeScript, Vite 5, Tailwind CSS 4.1, TanStack Query ·
**AI:** any OpenAI-compatible API (Groq, OpenAI, OpenRouter) or a fully offline extractor ·
**Hosting:** Render (API), Vercel (web app), Neon (Baton's home database), GitHub Actions

## Run locally

Requirements: JDK 21, Docker, Node.js 20+.

```bash
docker compose up -d          # Postgres 16 + pgvector on localhost:5433
./mvnw spring-boot:run        # API on http://localhost:8080
```

```bash
cd frontend
npm install
npm run dev                   # http://localhost:5173
```

Sign in with workspace `demo`. The synthetic **Project Annapurna** team is seeded on first start; every demo person's
password is `annapurna-demo`.

| Person | Email | Role |
|---|---|---|
| Neha Kulkarni | neha@annapurna.test | Project lead |
| Priya Sharma | priya@annapurna.test | Leaving |
| Arjun Mehta | arjun@annapurna.test | Incoming engineer |
| Rahul Desai | rahul@annapurna.test | Incoming designer |
| Org Admin | admin@annapurna.test | Organisation admin |

Load the seven demo sources and reconstruct Priya's handover, then score it:

```bash
bash demo/load-demo.sh
python demo/evaluate.py
```

To sign up a test company against a local database, start the API with `ALLOW_PRIVATE_DB_HOSTS=true`.

## Tests

```bash
./mvnw test                   # 115 tests against a real pgvector container (needs Docker)
```

They also run on every push in [GitHub Actions](.github/workflows/ci.yml), together with the frontend build.

**Evaluation:** two synthetic projects with answer keys. Project Annapurna plants 25 facts
([`planted-facts.json`](demo/annapurna/planted-facts.json)); Project Godavari is harder: 25 plainly stated, partly
Hinglish facts plus 7 traps (a joke, a rumour, a rejected idea, sarcasm, superseded values) and an exhaustive answer
key for precision ([`planted-facts.json`](demo/godavari/planted-facts.json)). Offline rules find **23 of 25** on
Annapurna and **8 of 25** on Godavari, with no leaked secrets, no permission leaks and no trap shown as fact.
`python demo/benchmark.py` compares offline mode with language models on recall, precision, traps, tokens and cost
([report](demo/benchmark-report.md)).

**Model check:** with a model connected, reconstruction runs a second model pass that asks whether each verified
quote really supports its claim. A "not supported" verdict stops the claim being shown as fact until a person decides.

## Configuration

Everything is set with environment variables. The important ones for production:

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Baton's home database |
| `JWT_SECRET` | Token signing (≥ 32 bytes) |
| `CRYPTO_KEY` | Master key, `openssl rand -base64 32` — back it up, never change it |
| `CORS_ORIGINS`, `APP_URL` | The web app's address |
| `RESEND_API_KEY`, `MAIL_FROM` | Turns email on (password reset, invitations, share notices) |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` | Turns online payment on |
| `FREE_PEOPLE`, `FREE_PROJECTS`, `FREE_SOURCES`, `TEAM_PRICE_MINOR` | Plan limits and price |
| `AI_MOCK`, `GROQ_API_KEY`, `EMBEDDINGS_ENABLED` | Server-wide AI and embeddings |

Frontend build: `VITE_API_BASE` (API address), `VITE_DEMO_PASSWORD` (shows the demo people).
The full list with defaults is in [the documentation](docs/DOCUMENTATION.md#14-configuration-reference).

## Deployment

Every push to `main` deploys both parts: Vercel builds the web app from GitHub, and
[`.github/workflows/deploy-api.yml`](.github/workflows/deploy-api.yml) triggers the Render deploy of the API
(Docker image from [`Dockerfile`](Dockerfile)). Details in [the documentation](docs/DOCUMENTATION.md#15-deployment).

## Repository layout

```
src/main/java/…/baton/   API (admin, ai, billing, handover, identity, insight, integration, reconstruct, review, source, tenant, …)
src/main/resources/      Settings, database migrations, AI prompts
src/test/                Tests
frontend/                Web app
demo/                    Synthetic demo sources
design/figma-plugin/     Figma plugin that draws the product design
docs/                    Documentation
```

## Data

Demonstrations use synthetic data only (Project Annapurna is made up). No real club or company messages are used.

## AI disclosure

AI coding assistants were used to generate parts of the code from the team's own specification; the team designed,
reviewed and tested it. Inside the product, AI models run only when a workspace connects its own provider (or the
server is configured with one), and only on redacted text.
