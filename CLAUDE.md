# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Camunda 8 process demo for Safe Software's Camunda trial: an internal, AI-assisted Support QA
process. Spring Boot web app (Java 17, Camunda 8.8) that deploys BPMN/DMN/form resources on
startup and connects to a Camunda 8 SaaS cluster (credentials in `application.properties`).
Server runs on port **8082**. Source context in `src/main/resources/context/`: `Safe Software -
Camunda Proposal - Support_QA_Process_Overview.pdf` (process design), `QA Review.bpmn` (the
customer-designed reference model, see below), `QAScoring.pdf` (the rubric's weighted-scoring
formula), and `ZendeskTicketSample/` (a real anonymized ticket used to ground the mocks).

## Use Case: Support QA Review

Safe Software's Support Team Leads (TLs) aligned on a shared 9-category QA rubric (Communication,
Technical Advice, Process Adherence — 3 categories each, scored 4-Excellent/3-Meets
Standard/2-Developing/1-Does Not Meet, or NotApplicable) and are building an internal,
AI-assisted review process rather than buying a commercial QA platform. The trial's primary
purpose is evaluating **Camunda as infrastructure for agentic orchestration** — governance,
auditability, controlled tool/data access, cost/token control, and human oversight — not proving
AI QA-scoring quality.

Process id: `SupportQaReview` (`bpmn/support-qa-review.bpmn`). Shaped to match
`context/QA Review.bpmn`, the reference model Safe Software's Support Team Leads designed with us
during the trial scoping — same "Ticket Scoring" grouping, parallel per-dimension evaluations,
Overall Score Determination / Persist Score split, and boundary-based review gateway, adapted to
run on connectors/credentials we actually have wired up (see deviations below).

Split across five deployable BPMN files, each its own top-level process invoked via Camunda 8 call
activities (`zeebe:calledElement`, `bindingType="latest"`, default `propagateAllChildVariables`) so
every stage can be deployed/versioned independently instead of nesting everything as embedded
subprocesses in one file:
- `support-qa-review.bpmn` (`SupportQaReview`) — the main flow (steps 1-3 and 5-7 below), calling
  out to `TicketScoring` for step 4.
- `ticket-scoring.bpmn` (`TicketScoring`) — the fork/join and Overall Score Determination/Persist
  Score steps, calling out to the three dimension processes below.
- `communication-evaluation.bpmn` (`CommunicationEvaluation`), `technical-advice-evaluation.bpmn`
  (`TechnicalAdviceEvaluation`), `process-adherence-evaluation.bpmn`
  (`ProcessAdherenceEvaluation`) — one process per rubric dimension, each just a start event, the
  ad-hoc AI-agent subprocess, and an end event.
Call activities propagate variables in and out by default (no explicit `zeebe:input`/`zeebe:output`
mappings), so this behaves like the prior embedded-subprocess design from the job workers'
perspective — variable names and scoping (`comm/tech/proc` prefixes, `jiraIssueKey` etc.) are
unchanged, just spread across separate process instances joined by parent/child links.

1. **Ticket closed** — start event takes `zendeskTicketId` (no Zendesk webhook wired up yet;
   start it manually via `/start` with that one variable).
2. **Retrieve controlled ticket snapshot** (`fetch-ticket-snapshot`) — mocked in
   `SupportQaWorkers.java`, seeded by `zendeskTicketId`: subject, customer/agent names, channel,
   tags, reply count, thread excerpt.
3. **Deterministic checks** (`run-deterministic-checks`) — the mechanically verifiable signals:
   `firstResponseMinutes`/`slaMet`, `jiraLinked`/`jiraIssueKey`, `responseCadenceFlag`,
   `internalNotesPresent`, `followUpCommitmentMade`/`followUpHonoredOnTime`, and
   `ticketInvolvesBug`/`jiraBugTicketScore` (the Jira Bug Ticket rubric category — determined here,
   not by an agent).
4. **Ticket Scoring** (`CallActivity_TicketScoring` → `TicketScoring` process) — three independent
   AI-agent evaluations run in parallel (`bpmn:parallelGateway` fork/join in `ticket-scoring.bpmn`,
   each branch a call activity into its own process), each an ad-hoc sub-process on the
   `io.camunda.agenticai:aiagent-job-worker:1` connector (OpenAI `gpt-4o-mini`, secret
   `{{secrets.openAiApiKey}}`), scoring one rubric dimension with evidence + confidence per
   category:
   - `CommunicationEvaluation` (`communication-evaluation.bpmn`) —
     `AdHocSubProcess_CommunicationEvaluation`: toneProfessionalism, nextStepsOwnership,
     callChannelJudgment. Tool: `get-rubric`.
   - `TechnicalAdviceEvaluation` (`technical-advice-evaluation.bpmn`) —
     `AdHocSubProcess_TechnicalAdviceEvaluation`: rootCauseIdentified, technicalAccuracy,
     completeness. Tool: `get-rubric`.
   - `ProcessAdherenceEvaluation` (`process-adherence-evaluation.bpmn`) —
     `AdHocSubProcess_ProcessAdherenceEvaluation`: jiraBugTicket (carries through the mechanical
     `jiraBugTicketScore` unchanged), internalNotes, followUpCommitments. Tools: `get-rubric`,
     `lookup-jira-issue` (reads the already-in-scope `jiraIssueKey`, propagated in from the root
     process instance through two call-activity boundaries).
   Each writes `{comm,tech,proc}CategoryScores`/`{comm,tech,proc}AnomalyFlagged`/
   `{comm,tech,proc}AnomalyReasons` (strict JSON via `data.response.format.parseJson=true`) —
   distinct variable names because parallel branches share one variable scope. None of the three
   assigns the final rating.
   After the join, **Overall Score Determination** (`determine-overall-score`, plain Java, not an
   AI call) merges the three category-score maps into one 9-category `categoryScores`, computes
   `lowestCategoryScore` and ORs the three anomaly flags/reasons into `anomalyFlagged`. It also
   computes `overallScorePercent` using the TLs' weighted-scoring formula from `context/QAScoring.pdf`
   (`CATEGORY_WEIGHTS` in `SupportQaWorkers.java`: Communication 3/3/1, Technical Advice 3/3/2,
   Process Adherence 1/1/1, total weight 18 — `overallScorePercent = sum(score x weight) /
   sum(4 x weight)` across scored, non-NotApplicable categories only) and derives
   `overallProvisionalRating` from it: >=87.5% Exceeds, >=62.5% Meets, else NeedsImprovement. Those
   band edges aren't specified by the TLs — they're the midpoints between the PDF's own reference
   points (straight-2s=50%, straight-3s=75%, straight-4s=100%); adjust `determineOverallScore` if
   the TLs set different cutoffs. **Persist Score** (`persist-score`) assembles and caches the
   resulting `qaAssessment` (including `overallScorePercent`) for audit — the "AIScore" write in the
   reference model.
5. **Selection engine** — deterministic part is a DMN business rule task
   (`dmn/qa-review-selection-rules.dmn`, decision `QaReviewSelectionRules`, hit policy FIRST):
   any category `<= 2` or `anomalyFlagged` always triggers review (`triggerReview`/`triggerReason`).
   `apply-sample-selection` layers the two sampling channels (high-score spot-check, random
   baseline) on top using TL-configurable `spotCheckSamplePercent`/`randomBaselineSamplePercent`
   (default 5%/3%), producing the final `routeToHumanReview`/`selectionChannel`/`selectionReason`.
   This DMN + sampling engine is the deterministic implementation behind the reference model's
   single "What is the score? / Within-Outside Boundary" gateway.
6. **Routing** — `routeToHumanReview` false skips straight to recording (the ~92-95% case, "Within
   Boundary" in the reference model); true creates `Task_TlReview` ("Outside Boundary" /
   "Manual Review" in the reference model, candidate group `support-team-leads`) where the TL sets
   `finalRating`, `coachingNotes`, `reviewedBy`.
7. **Record QA result** (`record-qa-result`) — merges provisional + human outcome into
   `effectiveRating`/`finalRatingSource` (`human` vs `ai-unreviewed`)/`overridden`, for audit. This
   is the "Persist Score with notes" / adjustedScore write in the reference model, extended to run
   on both branches (not just the reviewed one) so every ticket gets a final audit record.

**Deviations from the `QA Review.bpmn` reference model** (deliberate, to stay runnable without new
credentials/integrations — see conversation history for the reasoning): the three dimension
evaluations all use the same OpenAI agent-job-worker connector rather than the modeled mix of a
nested Anthropic agent-as-tool call and raw Azure OpenAI legacy-completions HTTP calls; "Manual
Review" is a Camunda user task with a form-free JSON payload rather than a Slack message; and the
modeled Zendesk/Database pools and message flows (`AIScore`, `adjustedScore`) are represented as
plain start-event input and in-process `ProcessVariableCache` merges rather than real message
flows to external systems.

## Build & Run

Uses the Maven wrapper (`./mvnw`). Docker required only for Testcontainers-based tests.

```bash
./mvnw clean package -DskipTests   # build
./mvnw spring-boot:run             # run → http://localhost:8082
./mvnw test                        # run tests (Docker required)
```

## Architecture

- **Entry point**: `LoanOriginationMortgageApplication` (package `com.camunda.loanoriginationmortgage` —
  kept as-is from an earlier scaffold; renaming would require too many cross-file changes).
- **`ResourceDeployer`**: auto-deploys every `bpmn/**/*.bpmn`, `dmn/**/*.dmn`, `forms/**/*.form` on startup,
  in one batched deployment command — this is how the five Support QA Review BPMN files (main process
  plus the `TicketScoring`/`CommunicationEvaluation`/`TechnicalAdviceEvaluation`/
  `ProcessAdherenceEvaluation` call-activity targets) all become resolvable by process id at once.
  No manual deploy step needed — add files under those folders and restart.
- **`ProcessController`**: `POST /api/process/start` (start any process by id + variables),
  `GET /api/process/recent` (last 50 instances started this session, in-memory).
- **`TasksController`**: `GET /api/tasks` (list open user tasks across all processes, enriched with
  each instance's starting variables), `GET /api/tasks/{taskKey}/context` (full variable context for
  a task), `POST /api/tasks/{taskKey}/complete` (complete a task with output variables).
- **`AdminController`**: `GET /api/admin/config` (Operate/Tasklist base URLs for the configured
  cluster), `GET /api/admin/instance-step/{piKey}` (current active element for an instance).
- **`ProcessVariableCache`**: in-memory map of process-instance-key → variables, merged in by job
  workers as service tasks complete. Lets the task inbox show full context without depending on the
  Camunda variables/search API (which lags for just-set variables in SaaS).
- **`CamundaRestHelper`**: thin REST wrapper for the Camunda v2 API (user-task search, variable
  search, element-instance search).
- **`WebMvcConfig`**: redirects `/` → `/index.html`, forwards `/start`, `/tasks`, `/admin` to their
  static pages.
- **Static pages** (`static/index.html`, `start.html`, `tasks.html`, `admin.html`): a generic,
  business-logic-free portal — start any process by id + JSON variables, browse/complete open user
  tasks with raw variable JSON, and a dashboard of recent instances with live step tracking. Restyle
  or replace once a real use case is defined.
- **`SupportQaWorkers`**: `@JobWorker`-annotated methods for every service task/tool in
  `support-qa-review.bpmn` — ticket snapshot and deterministic checks are seeded mocks; get-rubric
  and lookup-jira-issue are the AI agents' approved tools; determine-overall-score and
  persist-score are the deterministic combine/persist steps after the three parallel dimension
  evaluations; apply-sample-selection and record-qa-result drive the review-routing/audit steps.

## Key Gotchas

- **Java package name**: `com.camunda.loanoriginationmortgage` is kept from the original scaffold.
  Do not rename it without updating all import statements, the Spring Boot application class, and tests.
- **`ResourceDeployer`** deploys everything under `bpmn/`, `dmn/`, `forms/` — add new files there and
  they are auto-deployed on the next startup.
- **`application-demo.properties`**: alternative profile for demo-specific overrides. Activate with
  `--spring.profiles.active=demo`.
- **`application.properties`** contains live Camunda SaaS cluster credentials — treat as a secret,
  don't commit new/rotated credentials without checking with the user.

## Status

The Support QA Review use case (see above) is built end to end: the five `bpmn/*.bpmn` process
files (`support-qa-review.bpmn`, `ticket-scoring.bpmn`, `communication-evaluation.bpmn`,
`technical-advice-evaluation.bpmn`, `process-adherence-evaluation.bpmn`),
`dmn/qa-review-selection-rules.dmn`, and `SupportQaWorkers.java` are all deployed/registered.
`context/QA Review.bpmn` is kept in the repo as the customer-designed reference model the
executable process is shaped after. It deliberately lives under `context/`, not `bpmn/` — it's not
independently deployable (raw Azure OpenAI completions/Slack connector templates with no task
definitions, a gateway missing conditions) and `ResourceDeployer` deploys every `bpmn/**/*.bpmn`
file in one batched command, so an invalid file in that folder would fail deployment for the real
process too.

Starting a process instance with `zendeskTicketId` = `12345` replays a real, anonymized Safe
Software support ticket (`context/ZendeskTicketSample/`, an FME Flow 2026.2 native-looping
regression, Jira issue `FMEFORM-38238`) end to end instead of the synthetic seeded mock — useful
for demoing against a genuine example. Any other ticket id falls back to the random seeded mock.

**Not yet modeled** (per the process overview PDF, these are separate-cadence activities, not part
of the per-ticket flow): the periodic TL calibration loop (re-scoring tickets to refine the
rubric/AI confidence thresholds) and the weekly Support Brief / monthly Product-Dev Brief rollups.

**Not yet built**: UI wiring (forms, static pages) for this use case — the generic `/start` and
`/tasks` portal (raw JSON in, raw JSON out) is sufficient to run and test the process end to end
in the meantime.
