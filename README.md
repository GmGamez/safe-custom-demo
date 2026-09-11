# Safe Custom Demo — Support QA Review (Camunda 8)

Spring Boot (Java 17) + Camunda 8.8 app built for Safe Software's Camunda trial: an internal,
AI-assisted Support QA review process. It deploys BPMN/DMN process resources to a Camunda 8 SaaS
cluster on startup, runs three parallel AI-agent evaluations per ticket (Communication, Technical
Advice, Process Adherence), deterministically combines them into a weighted score, and routes a
sample of tickets to a human Team Lead review task.

See `CLAUDE.md` for the full process design and architecture writeup. This README covers getting
it running and pointing it at a different Camunda cluster.

---

## Prerequisites

| Requirement | Notes |
|---|---|
| Java JDK 17+ | |
| Maven | Included via the `./mvnw` wrapper — nothing to install |
| A Camunda 8 SaaS cluster | Free trial at [camunda.com](https://camunda.com) |
| An LLM API key | Any OpenAI-compatible endpoint (see below) |
| Docker | Only needed to run `./mvnw test` (Testcontainers) |

---

## Setup

### 1. Camunda cluster credentials

Edit `src/main/resources/application.properties`:

```properties
server.port=8082
camunda.client.cloud.cluster-id=YOUR_CLUSTER_ID
camunda.client.cloud.region=YOUR_REGION
camunda.client.auth.client-id=YOUR_CLIENT_ID
camunda.client.auth.client-secret=YOUR_CLIENT_SECRET
camunda.client.prefer-rest-over-grpc=false
camunda.client.rest-address=https://YOUR_REGION.zeebe.camunda.io/YOUR_CLUSTER_ID
```

Find these under **Camunda Console → your cluster → API tab → Create new client** (grant it
Zeebe access). This file currently contains live credentials — treat it as a secret, don't commit
new/rotated ones without checking first.

### 2. LLM credentials (Camunda cluster secrets, not `application.properties`)

The three AI-agent evaluation steps (`communication-evaluation.bpmn`,
`technical-advice-evaluation.bpmn`, `process-adherence-evaluation.bpmn`) call an OpenAI-compatible
endpoint via two **Camunda cluster secrets**, referenced in the BPMN as `{{secrets.LLM_API_ENDPOINT}}`
and `{{secrets.LLM_API_KEY}}`:

- `LLM_API_ENDPOINT` — base URL of an OpenAI-compatible chat completions endpoint
- `LLM_API_KEY` — API key for that endpoint

Set these under **Camunda Console → your cluster → Settings → Secrets**. Secrets are per-cluster,
so switching clusters means recreating both there — they are not stored anywhere in this repo.

The model itself (currently `anthropic.claude-sonnet-4-6`, via an OpenAI-compatible proxy) is
hardcoded per BPMN file in each ad-hoc sub-process's `provider.openaiCompatible.model.model` input —
edit those three files directly to change it.

### 3. Build and run

```bash
./mvnw clean package -DskipTests   # build
./mvnw spring-boot:run             # run → http://localhost:8082
```

On startup, `ResourceDeployer` deploys every file under `src/main/resources/{bpmn,dmn,forms}` to
whichever cluster is configured — no manual deploy step. Check the startup logs for
`ResourceDeployer : [deploy] N resource(s) deployed` to confirm it reached the cluster.

### 4. Team Lead review task assignment

`Task_TlReview` (the human review step) is assigned to candidate group `support-team-leads`. For a
new cluster, make sure that group exists and has members in **Camunda Console → Identity**, or
Tasklist users won't see the task in their inbox.

---

## Running a ticket through the process

Start an instance via the generic portal at `/start`, or directly:

```bash
curl -X POST http://localhost:8082/api/process/start \
  -H "Content-Type: application/json" \
  -d '{"processId":"SupportQaReview","variables":{"zendeskTicketId":"12345"}}'
```

- `zendeskTicketId = 12345` replays a real, anonymized Safe Software ticket
  (`src/main/resources/context/ZendeskTicketSample/`). Any other value falls back to a random
  seeded mock.
- To guarantee the ticket routes to human review regardless of AI scoring (useful for testing),
  add `"randomBaselineSamplePercent": 100` to the variables.

Open tasks (including `Task_TlReview`) show up at `/tasks`; instance progress at `/admin`.

---

## Connecting to a different cluster — checklist

1. **`application.properties`** — replace `cluster-id`, `region`, `client-id`, `client-secret`,
   `rest-address` with the new cluster's values (Console → API tab).
2. **Cluster secrets** — recreate `LLM_API_ENDPOINT` and `LLM_API_KEY` under the new cluster's
   Console → Settings → Secrets.
3. **Identity/candidate groups** — make sure `support-team-leads` exists with members, or the TL
   review task will have no one to claim it in Tasklist.
4. **Restart the app** — `ResourceDeployer` deploys all `bpmn/dmn/forms` resources to the new
   cluster automatically on the next startup. No separate deploy command needed.
5. Nothing else in the code references the old cluster — `AdminController`'s Operate/Tasklist links
   are derived from the same `cluster-id`/`region` config, so they'll follow automatically.

`application-demo.properties` is an alternate profile for demo-specific overrides — activate with
`--spring.profiles.active=demo` if needed; it doesn't currently override cluster settings.

---

## Architecture

```
Browser
  ├── /start    → starts a process via POST /api/process/start
  ├── /tasks    → lists + completes Camunda user tasks via /api/tasks
  └── /admin    → shows process instances + current step via /api/admin

Spring Boot (port 8082)
  ├── ResourceDeployer     — deploys bpmn/dmn/forms to Camunda on startup
  ├── ProcessController    — start process, list recent instances
  ├── TasksController      — list tasks, fetch variables, complete tasks, bulk-finalize reviews
  ├── AdminController      — instance step tracking, Operate/Tasklist URLs
  ├── SupportQaWorkers     — job workers: ticket snapshot, deterministic checks, score
  │                          aggregation, sampling, audit record
  ├── CamundaRestHelper    — thin wrapper over the Camunda v2 REST API
  └── ProcessVariableCache — in-memory variable store for task/dashboard context

Camunda 8 SaaS
  ├── SupportQaReview                 (support-qa-review.bpmn)
  ├── TicketScoring                   (ticket-scoring.bpmn)
  ├── CommunicationEvaluation         (communication-evaluation.bpmn)      ─┐ AI agent
  ├── TechnicalAdviceEvaluation       (technical-advice-evaluation.bpmn)   ─┤ (parallel,
  ├── ProcessAdherenceEvaluation      (process-adherence-evaluation.bpmn)  ─┘  gpt/claude)
  └── QaReviewSelectionRules          (dmn/qa-review-selection-rules.dmn)
```

Full per-step process design, rubric weighting formula, and the deliberate deviations from the
customer-designed reference model (`src/main/resources/context/QA Review.bpmn`) are documented in
`CLAUDE.md`.

---

## Troubleshooting

**App starts but resources don't deploy** — check startup logs for `ResourceDeployer`; an error
there usually means bad cluster credentials in `application.properties`.

**AI agent tasks fail / incident on the ad-hoc sub-process** — usually a missing or wrong
`LLM_API_ENDPOINT`/`LLM_API_KEY` cluster secret. Check Camunda Operate for the incident's error
message.

**Rubric category shows blank/NotApplicable for every category** — the model's raw reply didn't
parse as pure JSON. `determine-overall-score` (`SupportQaWorkers.java`) has a fallback that
extracts the embedded JSON object from the raw response text, but check Operate for the process
instance's `{comm,tech,proc}ResponseText` variables if scores still come back empty.

**Task inbox shows no `Task_TlReview` tasks** — most tickets skip human review by design (~92-95%
sampling boundary). Start an instance with `randomBaselineSamplePercent: 100` to force one.

**Operate/Tasklist links don't open the right instance** — confirm `cluster-id`/`region` in
`application.properties` match the cluster you're actually deploying to.
