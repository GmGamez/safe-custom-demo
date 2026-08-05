# Camunda 8 Process Demo — Scaffold

A minimal Spring Boot + Camunda 8 scaffold: a self-contained web portal for starting processes,
completing user tasks, and monitoring instances — with no business logic baked in yet. Use it as
the starting point for the next customer/industry demo.

---

## What's Here

- **Auto-deploy** — drop a BPMN process (plus optional DMN decision tables and forms) under
  `src/main/resources/{bpmn,dmn,forms}` and it's deployed to your Camunda 8 SaaS cluster on startup.
- **Generic portal** — `/start` to trigger any process by id with a JSON variable payload, `/tasks`
  to browse and complete open user tasks, `/admin` to watch instances progress in real time.
- **Generic REST API** — `ProcessController`, `TasksController`, `AdminController` work against
  whatever process you deploy; nothing is hardcoded to a specific process id or variable shape.

No BPMN/DMN/forms or job workers are currently deployed — the process directories are empty.

---

## Prerequisites

| Requirement | Version |
|---|---|
| Java JDK | 17 or higher |
| Maven | Included via `./mvnw` wrapper — nothing to install |
| Camunda 8 SaaS account | Free trial at [camunda.com](https://camunda.com) |

No Docker required for normal use (only for Testcontainers-based tests).

---

## Setup

### 1. Add your Camunda 8 credentials

Open `src/main/resources/application.properties` and set your cluster credentials. You can find
these in **Camunda Console → your cluster → API tab → Create credentials**.

```properties
camunda.client.cloud.cluster-id=YOUR_CLUSTER_ID
camunda.client.cloud.region=YOUR_REGION
camunda.client.auth.client-id=YOUR_CLIENT_ID
camunda.client.auth.client-secret=YOUR_CLIENT_SECRET
camunda.client.rest-address=https://YOUR_REGION.zeebe.camunda.io/YOUR_CLUSTER_ID
```

### 2. Build and run

```bash
./mvnw spring-boot:run
```

Open **http://localhost:8082** in your browser.

If `src/main/resources/{bpmn,dmn,forms}` are empty, `ResourceDeployer` logs a warning and skips
deployment — add resources and restart once you have a process to deploy.

---

## The Portal

### Start a Process (`/start`)
Enter a process id and a JSON blob of starting variables, then start an instance.

### Task Inbox (`/tasks`)
Lists all open Camunda user tasks across every deployed process. Click a task to see its raw
process variables and complete it with a JSON blob of output variables.

### Dashboard (`/admin`)
Shows every process instance started this session, with live current-step tracking and a link
into Camunda Operate for each row.

---

## Architecture

```
Browser
  ├── /start    → starts a process via POST /api/process/start
  ├── /tasks    → lists + completes Camunda user tasks via /api/tasks
  └── /admin    → shows process instances + current step via /api/admin

Spring Boot (port 8082)
  ├── ResourceDeployer     — deploys BPMN/DMN/forms to Camunda on startup
  ├── ProcessController    — start process, list recent instances
  ├── TasksController      — list tasks, fetch variables, complete tasks
  ├── AdminController      — instance step tracking, Operate/Tasklist URLs
  ├── CamundaRestHelper    — thin wrapper over the Camunda v2 REST API
  └── ProcessVariableCache — in-memory variable store for task context

Camunda 8 SaaS
  └── (nothing deployed yet — add BPMN/DMN/forms under src/main/resources/)
```

---

## Next Steps

To build out the next demo:
1. Author a BPMN process under `src/main/resources/bpmn/`
2. Author DMN decision tables (if any) under `src/main/resources/dmn/`
3. Author Camunda forms for user tasks under `src/main/resources/forms/`
4. Add a `@Component` with `@JobWorker`-annotated methods for each service task
5. Restart — `ResourceDeployer` picks up the new resources automatically

---

## Troubleshooting

**App starts but no resources deploy**
Check the startup logs for `ResourceDeployer` — with empty `bpmn/`, `dmn/`, `forms/` folders it
logs `no deployable resources found`, which is expected until a process is authored.

**Task inbox shows "No open tasks"**
The process needs to reach a user task step first. Start a process on the `/start` page.

**Operate link doesn't open the right instance**
Confirm your `cluster-id` and `region` in `application.properties` match your actual Camunda SaaS
cluster.
