# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Blank Camunda 8 process demo scaffold. Spring Boot web app (Java 17, Camunda 8.8) that deploys
BPMN/DMN/form resources on startup and connects to a Camunda 8 SaaS cluster (credentials in
`application.properties`). Server runs on port **8082**.

No business use case is defined yet — this is a clean slate ready for the next
customer/industry/use case. When one is chosen, author the BPMN process, DMN decision
table(s), and forms under `src/main/resources/{bpmn,dmn,forms}`, wire up real job workers,
and update this file's Use Case section.

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
- **`ResourceDeployer`**: auto-deploys every `bpmn/**/*.bpmn`, `dmn/**/*.dmn`, `forms/**/*.form` on startup.
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
- **No job workers are currently registered.** Add a `@Component` with `@JobWorker`-annotated methods
  (see git history's `MockJobWorkers.java` for the previous demo's pattern) once BPMN service tasks
  are defined.

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

Cleaned up from the previous Epic Games license-renewal demo — all business-specific BPMN, DMN,
forms, and job workers have been removed, and the static portal pages were reset to generic,
use-case-agnostic versions. The Spring Boot scaffold, deploy mechanism, and generic task/admin APIs
are intact and ready for the next use case.

Next steps once a use case is chosen:
- Author the main BPMN process under `src/main/resources/bpmn/`
- Author any DMN decision tables under `src/main/resources/dmn/`
- Author user task forms under `src/main/resources/forms/`
- Add job workers for the process's service tasks
- Update this file's Project Overview with the new use case
