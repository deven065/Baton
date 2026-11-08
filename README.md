# Baton

**Every handover sentence carries its receipt.** Baton turns a project's authorised records into a verified,
permission-aware handover, and turns whatever has no receipt into a question for the person leaving.

Team Bramhastra: Deven Rikame (lead, backend) · Bhushan Shinde (database, AI prompts) · Shawn Dmello (frontend, deployment)

## Run locally

Requirements: JDK 21, Docker.

```bash
docker compose up -d          # Postgres 16 + pgvector on localhost:5433
./mvnw spring-boot:run        # API on http://localhost:8080
```

On first start, Flyway creates the schema and the synthetic **Project Annapurna** team is seeded.
The demo password for every persona is `baton.demo.password` in `application.properties`.

| Persona | Email | Clearance | Groups | Project role |
|---|---|---|---|---|
| Neha Kulkarni (lead) | neha@annapurna.test | RESTRICTED | engineering, operations | PROJECT_LEAD |
| Priya Sharma (departing) | priya@annapurna.test | CONFIDENTIAL | engineering | MEMBER |
| Arjun Mehta (incoming engineer) | arjun@annapurna.test | CONFIDENTIAL | engineering | MEMBER |
| Rahul Desai (incoming design) | rahul@annapurna.test | INTERNAL | design | MEMBER |
| Org Admin | admin@annapurna.test | INTERNAL | — | not a member |

## Tests

```bash
./mvnw test                   # needs Docker: tests run against a real pgvector container
```

## Try it with the demo data

With the backend running, load the synthetic Project Annapurna sources, create Priya's handover and reconstruct it:

```bash
bash demo/load-demo.sh
```

See [demo/annapurna/README.md](demo/annapurna/README.md) for what is planted and what to expect.

## AI modes

| `AI_MOCK` | `GROQ_API_KEY` | Behaviour |
|---|---|---|
| `true` (default) | any | **Offline**: replays recorded Groq answers for identical prompts, else a rule-based extractor. No network. |
| `false` | set | **Groq** (`llama-3.3-70b-versatile`): cache → circuit breaker → rate limit → retry with back-off. Answers are cached, so a later offline demo replays them. |

Either way the model only proposes. Code verifies every quote, computes status and confidence, finds
conflicts and writes the exit questions.

## API so far (M1 + M2 + M3)

| Method | Path | Notes |
|---|---|---|
| POST | `/api/auth/login` | `{email, password}` → `{token, expiresAt, user}` |
| GET | `/api/me` | Current user, clearance and groups |
| GET | `/api/projects` | Only projects you are a member of |
| GET | `/api/projects/{id}` | 404 (never 403) if you are not a member |
| POST | `/api/projects/{id}/sources` | Multipart: `type`, `title`, `sensitivity`, `aclGroups`, `authorisationAttested=true`, and `file` or `repo` (+`branch`) for GitHub → 202 `{source, jobId}` |
| GET | `/api/projects/{id}/sources` | Sources you may see |
| GET | `/api/sources/{id}` · `/api/sources/{id}/documents` | 404 if not visible |
| POST | `/api/sources/{id}/sync` | Re-sync a GitHub source (lead only) |
| GET | `/api/jobs/{id}` | Job status and progress |
| GET | `/api/jobs/{id}/events` | Server-sent events: `progress` `{stage, percent, message, counts}`, then `done` |
| GET | `/api/chunks/{id}` | Evidence drawer; 404 if restricted (IDOR-safe) |
| GET | `/api/projects/{id}/search?q=` | Hybrid keyword + vector search, permission-filtered before ranking |
| POST | `/api/projects/{id}/handovers` | Lead only: `{departingUserId, title, scenario, aliases?}` |
| GET | `/api/projects/{id}/handovers` · `/api/handovers/{id}` | Lead and departing member |
| POST | `/api/handovers/{id}/reconstruct` | Lead only → 202 `{jobId}`; 409 if already running |
| GET | `/api/handovers/{id}/runs/latest` | Run status, counts, warnings |
| GET | `/api/handovers/{id}/claims?type=&status=&reviewState=&critical=&page=&size=` | Claims with verified evidence and links |
| GET | `/api/claims/{id}` | 404 if any cited chunk is hidden from you |
| GET | `/api/handovers/{id}/gaps` | Exit-interview questions |
| GET | `/api/handovers/{id}/timeline` · `/api/handovers/{id}/summary` | Decisions over time; counts |
| GET | `/actuator/health` | Public |

### Reconstruction pipeline (Flow B)
`SIGNAL` (task board and GitHub, no AI) → `EXTRACT` (redacted chunks in `<chunk>` tags, batched per access class) →
`VERIFY` (quote located in the chunk by code; verified quotes are cut from the source) → `CONSOLIDATE` →
`CONFLICT` → `SCORE` (status and confidence per LLD) → `GAP` (6–12 exit questions) → `TIMELINE`.
Stages are idempotent and resume after a crash. A claim is visible only if you may see **every** chunk it cites.

Source types: `DOCUMENT` and `MEETING_NOTES` (.md, .txt, .pdf, .docx…), `TASK_CSV` (Jira/Trello/GitHub Projects
export), `CHAT_EXPORT` (Slack export .zip or channel .json), `GITHUB` (read-only REST).

### What happens on ingest
Fetch → **redact** (emails, phones, API keys, passwords, private keys become tokens like `[API_KEY_1]`; originals
stored AES-GCM encrypted) → chunk → embed locally with MiniLM → store. Uploaded files are held encrypted only until
the job finishes, then deleted. Raw text is never stored in the clear.

Errors always look like `{"error": "CODE", "message": "...", "details": ...}` using the codes in LLD L9.

## Configuration

Secrets come from environment variables: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET` (≥ 32 bytes),
`CRYPTO_KEY` (base64, 32 bytes: `openssl rand -base64 32`), `GITHUB_TOKEN` (optional, read-only),
`EMBEDDINGS_ENABLED`,
`GROQ_API_KEY`, `AI_MOCK` (default `true`: replays recorded AI responses so the demo works offline),
`CORS_ORIGINS`, `DEMO_SEED`.

## Data

Demonstrations use synthetic data only. No real club or company messages are uploaded.

## AI disclosure

Claude (Anthropic) is used for code generation from our specification. Groq-hosted models run inside the product
at runtime on redacted text only.
