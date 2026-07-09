# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Camunda 8 demo: **Epic Games — software license optimization and renewal orchestration**.
Spring Boot web app (Java 17, Camunda 8.8) that deploys BPMN/DMN/form resources on startup and connects
to a Camunda 8 SaaS cluster (credentials in `application.properties`). Server runs on port **8082**.

### Use case

Automates the 90-day license renewal lifecycle for software applications used across Epic Games:
1. **Signal aggregation** — pull usage data from Okta SSO, Flexera on-premise metering, and vendor admin consoles
2. **Usage analysis & scoring** — DMN decision table resolves conflicting signals (SSO ≠ active use; on-prem data maps to device, not person) into a per-seat utilization score
3. **License reclamation** — identify and reclaim seats from departed users and accounts inactive ≥ 60–90 days
4. **App owner & manager outreach** — user task for access review approval before reclamation
5. **Legal ticket creation** — Jira ticket raised for contract review/amendment (mock `create_jira_legal_ticket` worker)
6. **Procurement submission** — final seat counts and vendor quote sent to Coupa PO (mock `submit_coupa_po` worker)
7. **Human-in-the-loop** — procurement/IT manager review task before PO submission

Key pain point addressed: a human currently must interpret heterogeneous usage signals manually. SSO logins
don't uniformly indicate active use; on-premise data maps to a device before any person-to-seat decision
can be made. Camunda orchestrates the signal aggregation and routes the decision to a human only when
ambiguous.

## Build & Run

Uses the Maven wrapper (`./mvnw`). Docker required only for Testcontainers-based tests.

```bash
./mvnw clean package -DskipTests   # build
./mvnw spring-boot:run             # run → http://localhost:8082
./mvnw test                        # run tests (Docker required)
```

## Architecture

- **Entry point**: `LoanOriginationMortgageApplication` (package `com.camunda.loanoriginationmortgage` —
  kept as-is; renaming would require too many cross-file changes).
- **`ResourceDeployer`**: auto-deploys every `bpmn/**/*.bpmn`, `dmn/**/*.dmn`, `forms/**/*.form` on startup.
- **`ProcessController`**: `POST /api/process/start` (start any process by id + variables),
  `GET /api/process/recent` (last 50 instances started this session).
- **`MockJobWorkers`**: stub workers for `fetch_okta_usage`, `fetch_flexera_usage`, `fetch_vendor_quote`,
  `identify_reclaimable_licenses`, `reclaim_licenses`, `create_jira_legal_ticket`, `submit_coupa_po`,
  `notify_app_owner`, `aggregate_usage_signals`. Replace with real integration calls as needed.
- **`CamundaRestHelper`**: thin REST wrapper for Camunda v2 API (user-task search, process-instance search).
- **`WebMvcConfig`**: redirects `/` → `/index.html`.
- **Static**: `static/index.html` — minimal landing page.

## Variable model (planned)

- `appId`, `appName`, `vendor`, `appOwner` — license subject
- `renewalDeadline` — ISO-8601 date, 90 days before expiration
- `requestedSeats` — current contracted seat count
- `oktaUsage` — `{ activeUsers, inactiveUsers, source }`
- `flexeraUsage` — `{ activeDevices, unmappedDevices, source }`
- `usageSummary` — `{ resolvedActiveUsers, oktaActive, flexeraActive, conflictDetected }`
- `reclaimable` — list of `{ userId, reason, lastLogin }`
- `reclaimableCount`, `reclaimedCount`
- `vendorQuote` — `{ vendor, seats, pricePerSeat, totalPrice, currency }`
- `jiraTicket` — `{ key, status, app }`
- `coupaPO` — `{ poNumber, status, amount }`
- `demo_outcome` — `approve | review | reclaim` (demo control knob)

## Key Gotchas

- **Java package name**: `com.camunda.loanoriginationmortgage` is kept from the original scaffold.
  Do not rename it without updating all import statements, the Spring Boot application class, and tests.
- **`ResourceDeployer`** deploys everything under `bpmn/`, `dmn/`, `forms/` — add new files there and
  they are auto-deployed on the next startup. No manual deploy step needed.
- **`CamundaRestHelper`** is wired into `AdminController` if one is added later. It expects the
  `camunda.client.rest-address` property to be set.
- **`application-demo.properties`**: alternative profile for demo-specific overrides. Activate with
  `--spring.profiles.active=demo`.

## Status

Scaffold cleaned up from a prior DIRECTV demo. BPMN, DMN, and forms are not yet created.
Next steps:
- Author `license-renewal.bpmn` (main process)
- Author `license-usage-scoring.dmn` (usage signal decision table)
- Author `license-renewal-review.form` (procurement manager user task)
- Add admin console HTML for the review queue
