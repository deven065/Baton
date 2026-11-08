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

## API so far (M1)

| Method | Path | Notes |
|---|---|---|
| POST | `/api/auth/login` | `{email, password}` → `{token, expiresAt, user}` |
| GET | `/api/me` | Current user, clearance and groups |
| GET | `/api/projects` | Only projects you are a member of |
| GET | `/api/projects/{id}` | 404 (never 403) if you are not a member |
| GET | `/actuator/health` | Public |

Errors always look like `{"error": "CODE", "message": "...", "details": ...}` using the codes in LLD L9.

## Configuration

Secrets come from environment variables: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET` (≥ 32 bytes),
`GROQ_API_KEY`, `AI_MOCK` (default `true`: replays recorded AI responses so the demo works offline),
`CORS_ORIGINS`, `DEMO_SEED`.

## Data

Demonstrations use synthetic data only. No real club or company messages are uploaded.

## AI disclosure

Claude (Anthropic) is used for code generation from our specification. Groq-hosted models run inside the product
at runtime on redacted text only.
