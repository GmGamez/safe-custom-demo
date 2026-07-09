# Demo Script: Epic Games License Renewal Orchestration

**Runtime:** ~12 minutes total (5–6 min per scenario)  
**App URL:** http://localhost:8082  
**Before you start:** Open the app and have the Dashboard tab ready in a second browser tab.

---

## Scenario 1 — Reclaim + Reduce: Slack Enterprise Grid

**The story:** IT suspects Slack is over-licensed after a reorganization. The renewal is 90 days out. You want to show how Camunda automatically gathers usage data, scores it, and only involves a human at the moments that require judgment.

---

### Step 1 — Set the scene *(30 seconds)*

> "Today I want to walk you through how Epic Games IT is automating their software license renewal process using Camunda 8. Right now, the IT team manually pulls data from Okta, from Flexera, and from vendor portals — then someone has to reconcile all of that before they can even start the renewal conversation. Camunda lets us orchestrate all of that automatically, and only pull a human in when there's a real decision to make."

Navigate to **http://localhost:8082**. Point to the three-step layout on the home page.

> "The flow is simple: someone triggers a renewal, the process runs automatically in the background, and users get notified when they need to act."

---

### Step 2 — Trigger the process *(1 minute)*

Click **Start a Renewal**. Click the **Slack Enterprise Grid** scenario card.

> "I'll pre-fill this with Slack — 200 seats currently contracted, and I've set the demo mode to 'reclaim and reduce.' This tells the mock workers to simulate a scenario where only 40% of seats are actively in use."

Click **Start renewal**.

> "Camunda has now started a process instance. In the background — simultaneously — it's calling out to Okta for SSO login data, Flexera for on-premise install data, and the vendor portal for a quote. All three in parallel. Not sequential. Not a human doing this one at a time."

Click **Review tasks →** in the success banner (or navigate to Dashboard).

---

### Step 3 — Show the process running *(1 minute)*

On the **Dashboard**, point to the Slack row showing the current step spinning through service tasks.

> "You can see the process working in real time. The parallel gateway fans out, the three fetch jobs complete, then it aggregates the signals and runs them through a DMN decision table."

Click **Operate ↗** to open the process instance in Camunda Operate.

> "In Operate we can see exactly where this instance is. Every token, every variable, every step — full visibility without touching a log file."

Switch back to your app tab. After a few seconds the row will show **'⏳ App Owner Review'**.

> "And here — the process has reached the first human step. Camunda identified 3 reclaimable seats: one departed employee and two accounts inactive for over 60 days. It's now waiting for the app owner to review and confirm."

---

### Step 4 — Complete the App Owner task *(1.5 minutes)*

Click **Review →** on the Slack row. The task inbox opens with the drawer.

> "This is the in-app task inbox. The app owner doesn't need to log into Camunda Tasklist or triage a ticket queue. The task comes to them with everything they need — the usage summary, which signals came from Okta versus Flexera, the recommendation from the decision table, and the specific users proposed for reclamation."

Point to the **Usage Summary** section.

> "DMN made the call: 40% utilization, low band, recommendation is 'reclaim and reduce.' Not a hard-coded rule — a decision table that the business team can maintain without a developer."

Point to the **Proposed Reclamations** table.

> "Three users: one departed, two inactive. The app owner reviews this, decides they agree, and approves."

Select **Approve Reclamation** and click **Submit Decision**.

> "Done. That variable is now written back into the process. Camunda advances: it reclaims the seats, raises a Jira legal ticket automatically, and immediately surfaces the next task — this time for the procurement team."

---

### Step 5 — Complete the Procurement task *(1.5 minutes)*

The inbox refreshes showing the **Procurement Approval** task. Click it to open.

> "Same pattern, different persona. Procurement sees the vendor quote, the updated seat count after reclamation, the Jira ticket number for the legal amendment, and the usage score. Everything in one place."

Point to the **Vendor Quote** — total price visible.

> "They can adjust the final seat count here before approving. Let's say they accept the reclaim recommendation — 197 seats."

Update the seat count, select **Approve PO**, click **Submit Decision**.

> "Camunda submits the Coupa PO automatically. The process is complete. From trigger to PO: fully automated except for two human decisions at the moments that actually needed judgment."

Check the Dashboard — the Slack row now shows **Completed**.

---

## Scenario 2 — Signal Conflict: GitHub Enterprise Cloud

**The story:** GitHub usage data is ambiguous — Okta shows one number, Flexera shows something significantly different. Nobody knows which is right. You want to show how Camunda detects this case and routes it differently rather than making a bad automated decision.

---

### Step 1 — Set the scene *(30 seconds)*

> "Now let me show you what happens when the data isn't clean. This is the case that breaks manual processes — when two systems disagree and a human has to figure out why before anything can move forward."

Navigate to **Start a Renewal**. Click **GitHub Enterprise Cloud** (conflict scenario, 150 seats).

> "This one is set to 'signal conflict.' The mock Flexera data and Okta data diverge significantly — more than the threshold we've defined as reliable."

Click **Start renewal**.

---

### Step 2 — Watch the DMN fire differently *(1 minute)*

Switch to the **Dashboard**.

> "Same process kicks off. Same parallel fetch, same aggregation. But when the signals hit the DMN decision table, something different happens."

Wait for the row to update. It will show **'⏳ App Owner Review'** again, but point to this.

> "The decision table has a guard rule at the top — FIRST hit policy. Rule 1: if `conflictDetected` is true, short-circuit everything and return 'escalate to human.' It doesn't even evaluate utilization. It can't trust the denominator."

> "This is the value of DMN. The business logic is explicit and auditable. You can open the decision table in Modeler and read exactly why the process took this path."

---

### Step 3 — Complete the conflict review task *(1.5 minutes)*

Click **Review →** on the GitHub row. Open the task drawer.

> "The app owner sees something different now. No clean reclamation list. Instead: conflict detected — yes — and the recommendation is 'escalate to human.' The system is telling them: we gathered the data, we can't reconcile it, you need to make the call."

Point to the **Conflict Detected** badge and the **escalate_to_human** recommendation.

> "Maybe Flexera is mapping to shared build machines, not individual developer seats. Maybe some employees use GitHub under a different identity. The system can't know — but a human can investigate. Camunda held the process here rather than automating a wrong answer."

> "The app owner reviews, adds a note explaining the discrepancy, and approves with a manual seat count."

Select **Approve Reclamation**, add a note like *"Flexera includes 12 shared build agents — human-verified 138 actual developer seats."* Click **Submit Decision**.

---

### Step 4 — Close the loop *(30 seconds)*

> "The rest of the process runs identically — Jira ticket, procurement approval, Coupa PO. The exception handling happened cleanly, at one checkpoint, with full audit trail."

Check the Dashboard — both instances now show Completed.

---

## Closing Talking Points

> "Two scenarios, same process definition. The difference in behavior came entirely from the DMN decision table and the gateway routing — not from code changes. If the business decides the conflict threshold should be 15% instead of 10%, that's a one-row change in the decision table, deployed without a developer."

> "The other thing to notice: every human decision is time-stamped, variable-captured, and visible in Operate. If anyone asks 'why did we reclaim that seat?' — the answer is in the process instance, not in someone's inbox."

> "That's the core story: Camunda handles the orchestration, the signal aggregation, and the routing logic. Humans handle the judgment. And the boundary between those two is explicit, auditable, and maintainable."

---

## Tips for a Live Demo

- **Pre-run both scenarios** before the presentation and let the service tasks complete — the user tasks will be waiting in the inbox, so you spend zero time watching spinners.
- **If Operate takes a moment to load**, keep talking — narrate what the audience *would* see.
- **The DMN table is a strong visual** if you have Camunda Modeler open alongside — showing the 4 rules side-by-side lands well with business audiences.
- **Scenario 2 works best second** — audiences need to see the clean path before the exception path resonates.
