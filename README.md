# Opsflow

Opsflow turns IT tickets into automated work. You open a ticket, and a triage service decides the priority (P1–P3) and the owning team. An orchestrator then picks a workflow template and runs it step by step against the action services: it notifies the team, opens a tracker issue, rolls out a deployment, records notes and closes out. Every step is retried, audited and visible in a web console.

It is a set of Spring Boot microservices that share one Postgres. Each service has its own database and role.

```
 browser / scripts
        │  Bearer token
        ▼
 ticket-service :8080 (published on 8090) ── console, /api, audit trail, job queue
        │ jobs: TRIAGE, START_WORKFLOW, CLOSE_ISSUES (retried with backoff)
        ├──► triage-service :8081        keyword rules (YAML), or an LLM with the rules as fallback
        └──► orchestrator-service :8082  workflow templates (YAML), one virtual thread per run
                 │  steps, each retried with backoff and an idempotency key
                 ├──► notification-service :8083  records + optional Slack webhook
                 ├──► deployment-service :8084    simulated rollouts, freezes, fault injection
                 └──► tracker-service :8085       INC-n issues + optional Jira Cloud mirror
                 ▲
                 └── reports the finished run back to ticket-service (/internal/runs/{id}/finished)

 all internal calls carry X-Internal-Token; only ticket-service is reachable from outside
```

## What it does

**Tickets**
- Tickets get keys OPS-1001, OPS-1002, and so on.
- You can search and filter by status, priority and team, with paging.
- Each ticket keeps a full audit trail. Every change, note, triage decision and workflow event becomes an event with the actor and the source service.
- You can override the priority and owner by hand. Later triage runs respect those overrides.
- Resolving a ticket closes its open tracker issues. Reopening puts the ticket back to work.

**Triage**
- The rules live in `triage-rules.yml`. Keywords match as whole words.
- For priority, the first level with a match wins: P1 for outage wording, P2 for errors, otherwise P3.
- For the owner, the team with the most keyword matches wins.
- Each decision comes with a confidence level, the reason and the matched words.
- Setting `OPSFLOW_LLM_API_KEY` turns on AI triage. It works with any OpenAI-compatible API (Groq by default). The answer is validated, and the service falls back to the rules if the model is down or answers nonsense.
- The console shows a live preview while you type a new ticket: "Triage would say P1 for devops-team and run Major incident (P1)".

**Workflows**
- There are five built-in templates: basic incident (the default), major incident, database problem, release request and access request.
- A template is chosen by the priority and team it matches. The `order` field breaks ties.
- You can add your own templates or replace a built-in one by dropping a YAML file into `./workflows`. Broken templates are listed with their errors instead of crashing the service.
- The step types are `notify`, `jira`, `deployment`, `log` and `complete`. Steps can use variables such as `${key}`, `${title}`, `${priority}` and `${team}`. Each step can set its own retries, and `continueOnError` turns a failure into a warning.
- You can start any workflow by hand on a ticket.
- A failed run can be retried from the step that failed. Steps that already succeeded are not repeated.

**Action services**
- Notifications are always recorded. They are also posted to Slack when a webhook is configured.
- Deployments go instance by instance, with health checks and rollback on failure. Each deployment keeps a log you can open in the console.
- You can freeze an environment, for example production, with a reason. A deployment to a frozen environment returns 409 and is not retried.
- Fault injection makes the next N deployments fail, so you can watch a workflow fail and be retried.
- The tracker keeps INC-n issues. It can mirror them to Jira Cloud (REST v3) and comment there when it closes them.

**Console** (served by ticket-service, no build step)
- Pages: tickets, new ticket with live triage preview, ticket detail with live run progress, dashboard, workflows and triage rules, activity, and system.
- The dashboard shows open tickets by priority, team and status, the time from ticket to finished workflow, new tickets per day, and recent activity.
- The activity page has tabs for notifications, deployments (with logs) and tracker issues.
- The system page shows the health of all six services, jobs waiting on another service, the integration status, deployment freezes and fault injection.
- An optional read-only viewer token suits a wallboard: the console hides every write control, and the API returns 403 for writes.
- The console works on phones and has a strict CSP with no inline scripts or styles.

## Run it

You need Docker with Compose. The stack uses about 2.5 GB of RAM.

```bash
cp .env.example .env          # optional: every value has a local default
docker compose up -d --build --wait
```

Open http://localhost:8090 and sign in with `opsflow-dev-token`. Your name is optional; it shows in the audit trail.

To try a failure:

1. On **System**, click "Fail the next one".
2. Create a ticket titled "Release search service 5.2". It goes to devops-team, P3, as a release request.
3. The staging deployment fails its health check, rolls back, and the run fails.
4. Click "Retry from failed step" on the ticket.

Stop the stack with `docker compose stop`. Use `docker compose down -v` to wipe the data as well.

### Running services outside Docker

Every service starts with an in-memory H2 database and local defaults, so you can run them one by one:

```bash
./mvnw -q install -DskipTests
java -jar services/triage-service/target/triage-service.jar   # repeat for each service
```

## Using the API

Every `/api` call needs `Authorization: Bearer <token>`. `X-Actor: <name>` is optional; it names you in the audit trail. Ready-made requests are in [`api-requests/`](api-requests).

```bash
T='Authorization: Bearer opsflow-dev-token'
curl -s -X POST localhost:8090/api/tickets -H "$T" -H 'Content-Type: application/json' \
  -d '{"title":"Payments API down for all users","description":"502 from the gateway","reporter":"noc"}'
# → 201 {"key":"OPS-1001","status":"NEW", ...}; triage and the workflow follow within seconds

curl -s localhost:8090/api/tickets/OPS-1001 -H "$T"         # priority, team, reason, status
curl -s localhost:8090/api/tickets/OPS-1001/runs -H "$T"    # every run with its steps
```

| Method | Path | What it does |
|---|---|---|
| GET | `/api/session` | Who you are and whether you can write |
| POST | `/api/tickets` | Create a ticket. Optional fields: `priority`, which fixes the priority by hand, and `workflow`, which forces a template |
| GET | `/api/tickets?q=&status=&priority=&team=&sort=&dir=&page=&size=` | Search, sort and page tickets |
| GET | `/api/tickets/{key}` | One ticket with its triage details and events |
| PATCH | `/api/tickets/{key}` | Change `priority` and `ownerTeam`, or set `status` to `RESOLVED` or `REOPENED` |
| POST | `/api/tickets/{key}/notes` | Add a note |
| POST | `/api/tickets/{key}/retriage` | Triage again; `{"rerun":true}` also starts a new workflow |
| POST / GET | `/api/tickets/{key}/runs` | Start a workflow (`{"workflow":"db-incident"}`, or empty for automatic) or list the runs |
| GET | `/api/runs`, `/api/runs/{id}` | All runs, or one run |
| POST | `/api/runs/{id}/retry` | Retry a failed run from the failed step |
| GET | `/api/events/recent` | Latest audit events |
| GET | `/api/workflows`, `/api/triage/rules` | Templates and triage rules |
| POST | `/api/triage/preview` | What triage and the workflow choice would be, without saving anything |
| GET | `/api/activity/{notifications,deployments,issues}` | What the action services did; `/api/activity/deployments/{id}` includes the log |
| GET | `/api/stats` | Dashboard numbers |
| GET | `/api/system` | Health of every service, waiting jobs, integrations, freezes |
| POST | `/api/system/jobs/retry` | Retry waiting jobs now |
| POST | `/api/system/chaos` | `{"failNext":N}` makes the next N deployments fail |
| PUT / DELETE | `/api/system/freezes/{env}` | Freeze (`{"reason":"..."}`) or unfreeze an environment |

Errors always have one shape:

```json
{"status":400,"error":"validation_failed","message":"Please fix the highlighted fields","fields":{"title":"Title is required"},"path":"/api/tickets","timestamp":"2026-11-14T04:20:00Z"}
```

The simple endpoints from the first version still work, for scripts that use them:
- `POST/GET /tickets` and `GET /tickets/{id}` on ticket-service. These need the API token.
- `POST /triage`, `POST /orchestrate/start?workflow=`, `POST /notify`, `POST /deploy` and `POST /jira/create` on the internal services. These need the internal token.

## Reliability details

- **No lost work.** Calls to triage and the orchestrator go through a job table and are retried with backoff (2 s doubling, capped at 60 s). If triage is down, tickets stay NEW and catch up once it is back. A refused job is marked failed and shows on the ticket.
- **Runs survive restarts.** Runs and steps are stored. If the orchestrator stops halfway, it resumes the run from the current step when it starts again.
- **No duplicate side effects.** Each step attempt sends an idempotency key (`runId:step:attempt`). A retried deployment, notification or issue is not created twice.
- **Retries that make sense.** Timeouts, 5xx and 429 are retried with exponential backoff, capped at 30 s. A 4xx (a frozen environment, say) fails the step at once.
- **The result always gets home.** If ticket-service is down when a run finishes, the orchestrator keeps the report and sends it again until it is accepted. The callback is idempotent.
- **Concurrent edits** are caught with optimistic locking and answered with a 409.

## Configuration

All settings come from environment variables. `.env.example` lists them with their defaults.

| Variable | Default | Purpose |
|---|---|---|
| `OPSFLOW_PORT` | `8090` | Port of the console and API |
| `OPSFLOW_API_TOKEN` | `opsflow-dev-token` | Full-access token, at least 12 characters |
| `OPSFLOW_VIEWER_TOKEN` | empty | Optional read-only token |
| `OPSFLOW_INTERNAL_TOKEN` | dev value | Secret shared by the services, at least 16 characters |
| `OPSFLOW_DB_PASSWORD`, `OPSFLOW_DB_ROOT_PASSWORD` | dev values | Postgres passwords |
| `OPSFLOW_TIMEZONE` | `Asia/Kolkata` | Day boundaries on the dashboard |
| `OPSFLOW_LLM_API_KEY`, `OPSFLOW_LLM_BASE_URL`, `OPSFLOW_LLM_MODEL` | empty, Groq, `llama-3.1-8b-instant` | AI triage |
| `OPSFLOW_SLACK_WEBHOOK_URL` | empty | Post notifications to Slack |
| `OPSFLOW_JIRA_BASE_URL`, `OPSFLOW_JIRA_EMAIL`, `OPSFLOW_JIRA_API_TOKEN`, `OPSFLOW_JIRA_PROJECT` | empty | Mirror issues to Jira Cloud (all four needed) |
| `OPSFLOW_TRACKER_PROJECT` | `INC` | Prefix of the built-in issue keys |
| `OPSFLOW_DEPLOY_ENVIRONMENTS` | `dev,staging,production` | Environments the deployment service knows |

The system page shows which integrations are live.

## Tests

```bash
./mvnw test                                    # 46 unit and slice tests, no Docker needed
docker compose up -d --build --wait
./mvnw -Pit verify -pl integration-tests -am   # 3 end-to-end tests against the running stack
```

The unit tests use fake HTTP servers for Slack, Jira and the LLM. They cover:
- the triage rules and the LLM fallback;
- template validation and selection;
- retries, `continueOnError`, resuming after a restart, and retrying from a failed step;
- idempotent notifications and deployments;
- freezes;
- access control for the viewer and operator tokens.

The end-to-end tests drive the real stack through three flows:
- a P1 ticket goes through every service, and resolving it closes its issue;
- an injected deployment failure is followed by a retry that completes;
- a frozen environment fails the run without retrying.

## Stack

Java 21, Spring Boot 4, Jackson 3, Flyway, PostgreSQL 16, virtual threads, SnakeYAML (safe loading), plain JavaScript console, Docker Compose.

## License

MIT
