# Epic Games IT — License Renewal Orchestration

A Camunda 8 demo showing how Epic Games IT automates their 90-day software license renewal lifecycle — from usage signal aggregation through procurement PO submission — with human-in-the-loop review at the right moments.

---

## What This Demo Shows

Software license renewals at scale involve data from multiple systems (Okta SSO, Flexera on-premise metering, vendor portals) that often disagree. Today, a human manually reconciles all of that before a renewal can even begin.

This demo replaces that manual work with a Camunda 8 orchestration process that:

1. **Aggregates signals in parallel** — Okta, Flexera, and vendor quote fetched simultaneously
2. **Scores utilization via DMN** — a decision table resolves conflicting signals into a recommendation (renew, reduce, reclaim, or escalate)
3. **Routes to the right human at the right time** — App Owner approves reclamation; Procurement approves the final PO
4. **Closes the loop automatically** — Jira legal ticket raised, Coupa PO submitted

The portal is a self-contained Spring Boot web app. Everything — including user task completion — runs in the browser without needing Camunda Tasklist.

---

## Prerequisites

| Requirement | Version |
|---|---|
| Java JDK | 17 or higher |
| Maven | Included via `./mvnw` wrapper — nothing to install |
| Camunda 8 SaaS account | Free trial at [camunda.com](https://camunda.com) |

No Docker required for normal use.

---

## Setup

### 1. Clone the repo

```bash
git clone https://github.com/YOUR-ORG/epic-games-license-renewal.git
cd epic-games-license-renewal
```

### 2. Add your Camunda 8 credentials

Open `src/main/resources/application.properties` and replace the placeholder values with your cluster credentials. You can find these in **Camunda Console → your cluster → API tab → Create credentials**.

```properties
camunda.client.cloud.cluster-id=YOUR_CLUSTER_ID
camunda.client.cloud.region=YOUR_REGION
camunda.client.auth.client-id=YOUR_CLIENT_ID
camunda.client.auth.client-secret=YOUR_CLIENT_SECRET
camunda.client.rest-address=https://YOUR_REGION.zeebe.camunda.io/YOUR_CLUSTER_ID
```

### 3. Build and run

```bash
./mvnw spring-boot:run
```

On first startup the app auto-deploys the BPMN process, DMN decision table, and Camunda form to your cluster. You'll see confirmation in the logs.

Open **http://localhost:8082** in your browser.

---

## The Portal

The app has three pages accessible from the navigation bar on every page:

### Start a Renewal (`/start`)
Trigger the license renewal process for any software application. Four pre-built scenario cards let you click to pre-fill the form:

| Scenario | Outcome |
|---|---|
| Adobe Creative Cloud | High utilization → renew as-is |
| Slack Enterprise Grid | Low utilization → reclaim + reduce |
| GitHub Enterprise Cloud | Signal conflict → escalate to human |
| Zoom Enterprise | Medium utilization → reduce seats |

Hit **Demo Data** to cycle through a randomized pool of 20+ enterprise apps.

### Task Inbox (`/tasks`)
Displays all open Camunda user tasks for the license-renewal process. Click any task to open a side drawer showing the full context — usage summary, reclaimable users, vendor quote — and a decision form to complete the task without leaving the app.

Two task types appear depending on where the process is:
- **App Owner: Review License Reclamation** — approve or reject the proposed reclamation list
- **Procurement: Approve License Renewal PO** — approve the final seat count and vendor PO

### Dashboard (`/admin`)
Shows all process instances started this session with live step tracking. Rows waiting for human review display a **Review →** button that takes you directly to the task inbox. Each row also has an **Operate ↗** link to open the instance in Camunda Operate.

---

## Demo Script

A step-by-step presenter script for two scenarios (Reclaim + Reduce and Signal Conflict) is at:

```
src/main/resources/scripts/demo-script.md
```

**Tip:** Start both scenarios before your presentation so the service tasks complete in the background. The user tasks will be waiting in the inbox when you're ready to demo.

---

## Architecture

```
Browser
  ├── /start    → starts license-renewal process via POST /api/process/start
  ├── /tasks    → lists + completes Camunda user tasks via /api/tasks
  └── /admin    → shows process instances + current step via /api/admin

Spring Boot (port 8082)
  ├── ResourceDeployer     — deploys BPMN/DMN/forms to Camunda on startup
  ├── ProcessController    — start process, list recent instances
  ├── TasksController      — list tasks, fetch variables, complete tasks
  ├── AdminController      — instance step tracking, Operate/Tasklist URLs
  ├── MockJobWorkers       — stub workers for all service tasks
  └── ProcessVariableCache — in-memory variable store for task context

Camunda 8 SaaS
  ├── license-renewal.bpmn          — main orchestration process
  ├── license-usage-scoring.dmn     — utilization scoring decision table
  └── license-renewal-review.form   — user task form definition
```

---

## Key Files

```
src/main/resources/
  bpmn/license-renewal.bpmn
  dmn/license-usage-scoring.dmn
  forms/license-renewal-review.form
  static/index.html          ← portal home
  static/start.html          ← trigger a renewal
  static/tasks.html          ← task inbox
  static/admin.html          ← dashboard
  scripts/demo-script.md     ← presenter guide
```

---

## Troubleshooting

**App starts but process doesn't advance past the first task**
Verify your cluster credentials in `application.properties`. Check the startup logs for `ResourceDeployer` — it will log success or failure for each deployed resource.

**Task inbox shows "No open tasks"**
The process needs to reach a user task step first. Start a renewal on the `/start` page and wait ~5–10 seconds for the service tasks to complete, then refresh the task inbox.

**Operate link doesn't open the right instance**
Confirm your `cluster-id` and `region` in `application.properties` match your actual Camunda SaaS cluster.
