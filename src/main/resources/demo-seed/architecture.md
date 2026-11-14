# Architecture notes

Backend is Spring Boot on Render; the database is Neon Postgres.
We decided to use Postgres instead of Firebase because we need SQL reports for the canteen.
Environment secrets live in the Render dashboard, not in the repo.
