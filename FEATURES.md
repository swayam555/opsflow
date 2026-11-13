# Feature map

This file lists every feature of the reference design Opsflow was built to cover: a ticket → triage → orchestrator → action-services platform with six Spring Boot services and Postgres. For each feature it shows where Opsflow implements it and how it was tested. It ends with what Opsflow adds on top.

Tests are marked:
- **U**: unit or slice test (`./mvnw test`);
- **E**: end-to-end test against the running stack (`-Pit`);
- **B**: checked by hand in the browser console.

## Reference features

| # | Reference feature | In Opsflow | Tested |
|---|---|---|---|
| 1 | Ticket service on 8080 accepts tickets (`POST /tickets`), lists them (`GET /tickets`), gets one (`GET /tickets/{id}`) | ticket-service on 8080 (published as 8090). `LegacyController` keeps the same three endpoints, accepting an id or an OPS key. The full API is under `/api/tickets` | U, E, B |
| 2 | Tickets stored in PostgreSQL with title, description, status, created time | `ticket` table (Flyway V1) holds those fields plus key, reporter, priority, owner team, triage reason and confidence, and version | U, E |
| 3 | Creating a ticket calls triage and then starts the orchestrator | The `Dispatcher` job queue runs TRIAGE, then START_WORKFLOW, retried with backoff, so a down service delays the work but loses nothing | U, E, B (triage-down drill) |
| 4 | Triage service on 8081 with `POST /triage` and `GET /triage/health` | triage-service on 8081. It has `POST /triage` (`mode=rules\|auto`), `GET /triage/health` and `GET /triage/rules` | U |
| 5 | Keyword triage: P1 for down/urgent/critical, P2 for error/issue/exception, otherwise P3 | `triage-rules.yml` keeps those words and adds more (outage, sev1, timeout, …). Matching is whole-word, so "download" no longer counts as "down" | U, B |
| 6 | Owner team from keywords: db, network, login/auth, deploy/ci | The same four teams, decided by the most keyword matches, with a `general-support` fallback | U, B |
| 7 | Triage returns priority, owner team and reason | `Verdict` returns priority, owner team, reason, confidence, matched words and the engine used | U, B |
| 8 | "AI planned": triage to be replaced by LLM classification | `LlmTriage`: any OpenAI-compatible API (Groq by default). The answer is validated and falls back to the rules | U (fake LLM server) |
| 9 | Orchestrator on 8082, `POST /orchestrate/start?workflow=` | orchestrator-service on 8082 keeps that endpoint. The full API is `/runs` and `/workflows` | U, E |
| 10 | Workflow templates in YAML (`incident-basic`: notify → jira → deployment(staging) → log → complete) | `WorkflowCatalog` loads `workflows/*.yml`. `incident-basic` has the same five steps, and there are four more templates. Custom templates go in `./workflows` | U, B |
| 11 | Executes steps one by one | `Engine` runs one virtual thread per run, with stored steps, retries and resume after a restart | U, E, B |
| 12 | Step "notify team" | `notify` step → notification-service | U, E, B |
| 13 | Step "create JIRA (mock)" | `jira` step → tracker-service INC-n issues, mirrored to real Jira Cloud when configured | U (fake Jira), E, B |
| 14 | Step "trigger deployment" | `deployment` step → deployment-service. The step polls until the rollout succeeds or fails | U, E, B |
| 15 | Step "add logs" | `log` step writes the message to the service log and keeps it as the step detail, shown on the ticket | U, B |
| 16 | Step "complete" / save the workflow completion record | `complete` step. The orchestrator reports the finished run to ticket-service (`/internal/runs/{id}/finished`), which records it in the audit trail and updates the ticket | U, E |
| 17 | Workflow record save endpoint (`POST /workflow/save`): ticket id, workflow name, status, timestamp | Replaced by the authenticated, idempotent callback above, plus run records (`GET /api/runs`, `GET /api/tickets/{key}/runs`) | U, E |
| 18 | Notification service on 8083, `POST /notify` (mock) | notification-service on 8083 keeps `POST /notify`. It also has `GET /notifications` and posts to Slack when a webhook is set | U (fake Slack), B |
| 19 | Deployment service on 8084, `POST /deploy` (mock CI/CD) | deployment-service on 8084 keeps `POST /deploy`. It runs simulated rollouts with logs, freezes and fault injection | U, E, B |
| 20 | JIRA service on 8085, `POST /jira/create` (mock) | tracker-service on 8085 keeps `POST /jira/create`. It also has `/issues` and the optional Jira Cloud mirror | U, B |
| 21 | Audit database: created tickets, completed workflows, status/history | `ticket_event` table: every create, triage, change, note, run start or finish, and failure, with actor and source. Shown on each ticket and the dashboard | U, E, B |
| 22 | REST endpoints for querying ticket and workflow status (SRS 4.5) | `/api/tickets/{key}`, `/api/tickets/{key}/runs`, `/api/runs/{id}`, `/api/events/recent`, `/api/stats` | U, E, B |
| 23 | Automatic triage and workflow selection (SRS 4.2) | `WorkflowCatalog.select(priority, team)` matches templates by priority and team, otherwise uses the default | U, B |
| 24 | Event-driven step execution (SRS 4.3) | Job queue plus background runs. Callers get 201/202 at once and the work continues asynchronously | U, E |
| 25 | Token-based API access (SRS non-functional) | Bearer token for `/api` with an optional read-only viewer token. `X-Internal-Token` between the services | U, B |
| 26 | Structured logs and health endpoints | `/actuator/health` on every service, a system page showing the health of all six, and one JSON error shape everywhere | B |
| 27 | Config-driven workflows (YAML/JSON) | YAML templates, validated at load, with broken ones listed together with their errors | U |
| 28 | Independent microservices in Docker Compose with PostgreSQL | Six services plus Postgres 16 in `docker-compose.yml`. Each service has its own database and role, and only ticket-service is published | E |
| 29 | Future: real Jira and Slack integrations | Both done, and optional (see `.env.example`) | U |
| 30 | Future: dashboard UI | Built-in console: tickets, ticket detail, dashboard, workflows, activity, system | B |
| 31 | Future: LLM suggestions | AI triage (row 8), with a live preview of triage and workflow choice while typing | U, B |

## Added in Opsflow

- **Fewer duplicate side effects:**
  - each step attempt carries an idempotency key;
  - failed runs retry from the failed step;
  - runs resume after a restart;
  - the run report is re-sent until ticket-service accepts it.
- **Manual control:** change the priority and owner, which later triage keeps; add notes; triage again; run any workflow by hand; resolve and reopen. Resolving closes the ticket's tracker issues.
- **Deployment controls:** deployment logs, environment freezes (409, not retried), and fault injection to rehearse failures.
- **Dashboard:** open tickets by priority, team and status, time to action, and new tickets per day.
- **Console safety:** strict CSP, a read-only mode, and a layout that works on phones.
- **Tests:** 46 unit tests and 3 end-to-end tests. The reference had only empty context-load tests.
