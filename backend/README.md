# Circular Analyser — Backend (v1.0.0)

Spring Boot 3.4 REST API for the Circular Analyser — a Compliance-team
workflow platform. See `..\circular-analyser-brd-v1.0.0.md` for the
requirements behind it.

## Stack

- **Java 21** source / bytecode (the build machine runs JDK 25; the project
  targets `<release>21</release>`)
- **Spring Boot 3.4** — Web, Data JPA, Validation
- **PostgreSQL 14+** via Hibernate 6 (jsonb mapping)
- **springdoc-openapi** — Swagger UI

## Prerequisites

- **JDK 21+** on the PATH (`java -version`)
- **Maven 3.9+** (`mvn -version`)
- The database from `../database/` running on `localhost:5432` —
  `docker compose up -d` in that folder. Schema + seed apply on first start.

## Run

```sh
mvn spring-boot:run
```

The API serves on `http://localhost:8080`. Swagger UI is at
`http://localhost:8080/swagger-ui.html`.

Other commands:

```sh
mvn compile        # compile only
mvn test           # run tests
mvn package        # build the runnable JAR (target/*.jar)
```

## Authentication (dev)

Production sign-in is Azure AD SSO (BRD FR-AUTH-01). Until that is wired,
every mutating endpoint expects an `X-User-Id` header carrying the signed-in
user's UUID (resolved by `CurrentUserArgumentResolver` to an `AppUser`).
Anonymous or unknown ids return **401**; disabled accounts return **403**.

`GET /api/auth/me` echoes the current user and records `last_login_at`.

## REST surface

All endpoints are under `/api`.

| Group | Endpoint |
|-------|----------|
| Auth | `GET /api/auth/me` |
| Meta | `GET /api/roles`, `GET /api/categories` |
| Dashboard | `GET /api/dashboard?year=&from=&to=` |
| Circulars | `GET /api/circulars?status=&search=` · `GET /api/circulars/years` · `GET /api/circulars/referred?refs=…` · `GET /api/circulars/{id}` |
| Circular actions | `POST /api/circulars/{id}/review` · `PUT /api/circulars/{id}/body` · `POST /api/circulars/{id}/forward` · `POST /api/circulars/bulk-forward` · `POST /api/circulars/{id}/reject` · `POST /api/circulars/{id}/in-action` · `POST /api/circulars/{id}/close` · `DELETE /api/circulars/{id}/forwardings/{forwardingId}` |
| Comments | `GET /api/circulars/{id}/comments` · `POST /api/circulars/{id}/comments` |
| Reminders | `GET /api/circulars/{id}/reminders` · `POST /api/circulars/{id}/reminders` · `GET /api/reminder-schedule` · `PUT /api/reminder-schedule` |
| Teams | `GET /api/teams` · `POST /api/teams` · `PATCH /api/teams/{id}` · `POST /api/teams/{id}/recipients` · `DELETE /api/teams/{id}/recipients/{recipientId}` |
| Users | `GET /api/users` · `POST /api/users` · `PATCH /api/users/{id}` · `GET /api/directory/search?q=` |
| Notifications | `GET /api/notifications?userId=…` · `POST /api/notifications/{id}/read` · `POST /api/notifications/read-all?userId=…` |
| Audit | `GET /api/audit?action=&search=&from=&to=` |

## Project layout

```
src/main/java/com/meridian/circular/
├── CircularAnalyserApplication.java
├── config/WebConfig.java                CORS + @Actor resolver
├── domain/                              JPA entities (16 — one per table)
├── repo/                                Spring Data JPA repositories
├── dto/Dtos.java                        Nested request + response records
├── security/Actor.java, CurrentUserArgumentResolver.java
├── service/                             Audit, Notification, Team, User,
│                                         Reminder, Dashboard, Circular
└── web/                                 REST controllers + exception handler
```

## Frontend wiring (later)

When this backend is up, point the frontend at it by replacing the function
bodies in `frontend/src/api/client.js` with `fetch` calls to these endpoints.
Pages and components stay unchanged. The frontend should send the signed-in
user's UUID in the `X-User-Id` header on every request.

## Not yet implemented

- Real Azure AD SSO / Graph API integration (the `searchDirectory` service
  uses a small hardcoded list as a stand-in).
- Background workers for scheduled reminders and SLA escalation — the DB
  tables (`reminder`, `reminder_schedule`, `sla_escalation_log`) and the
  manual reminder endpoint are in place; the scheduler loop is future work.
- The ingestion pipeline (poll process) that writes `circular`,
  `circular_analysis`, and `circular_document` rows. The API is ready to
  serve them; sample circulars can be inserted manually for dev demos until
  the engine is wired through.
