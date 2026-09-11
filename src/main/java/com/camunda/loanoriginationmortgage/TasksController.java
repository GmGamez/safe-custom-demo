package com.camunda.loanoriginationmortgage;

import io.camunda.client.CamundaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/tasks")
public class TasksController {

    private static final Logger log = LoggerFactory.getLogger(TasksController.class);

    private final CamundaRestHelper restHelper;
    private final ProcessController processController;
    private final ProcessVariableCache variableCache;
    private final CamundaClient client;

    public TasksController(CamundaRestHelper restHelper, ProcessController processController,
                           ProcessVariableCache variableCache, CamundaClient client) {
        this.restHelper = restHelper;
        this.processController = processController;
        this.variableCache = variableCache;
        this.client = client;
    }

    /**
     * The nine-category QA rubric, for a TL to reference while reviewing a ticket - the exact same
     * criteria table the AI agents scored against (SupportQaWorkers.rubricCategories()), not a
     * separately maintained copy.
     */
    @GetMapping("/rubric")
    public ResponseEntity<?> getRubric() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scale", "4-Excellent / 3-Meets Standard / 2-Developing / 1-Does Not Meet (or NotApplicable)");
        result.put("categories", SupportQaWorkers.rubricCategories());
        return ResponseEntity.ok(result);
    }

    /**
     * List all open user tasks, enriched with the variables the process instance was started with
     * plus the job-worker output cache (ticket snapshot, deterministic checks, qaAssessment, etc.)
     * so task list cards can show real context without a per-task round trip.
     */
    @GetMapping
    public ResponseEntity<?> listTasks() {
        try {
            Map<String, Object> res = restHelper.post("/user-tasks/search",
                Map.of("filter", Map.of("state", "CREATED")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) res.getOrDefault("items", List.of());

            List<Map<String, Object>> enriched = new ArrayList<>();
            for (Map<String, Object> task : items) {
                Map<String, Object> t = new LinkedHashMap<>(task);
                long piKey = asLong(t.get("processInstanceKey"));
                Map<String, Object> vars = new LinkedHashMap<>();
                processController.getRecentInstancesList().stream()
                    .filter(e -> piKey == asLong(e.get("processInstanceKey")))
                    .findFirst()
                    .ifPresent(entry -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> initVars = entry.get("variables") instanceof Map<?,?> m
                            ? (Map<String, Object>) m : Map.of();
                        vars.putAll(initVars);
                    });
                vars.putAll(variableCache.get(piKey));
                t.put("variables", vars);
                enriched.add(t);
            }
            return ResponseEntity.ok(enriched);
        } catch (Exception e) {
            log.error("[tasks] list failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get process variables for the given task.
     * Builds the variable map from three sources in priority order:
     *   1. Initial submission variables (in-memory, always available)
     *   2. Job worker output cache (set as service tasks complete)
     *   3. Camunda variables/search API (fallback for variables not in the above)
     */
    @GetMapping("/{taskKey}/context")
    public ResponseEntity<?> getTaskContext(@PathVariable long taskKey,
                                            @RequestParam long piKey) {
        try {
            Map<String, Object> variables = new LinkedHashMap<>();

            // 1. Initial submission variables (appName, vendor, appOwner, requestedSeats, etc.)
            processController.getRecentInstancesList().stream()
                .filter(e -> piKey == asLong(e.get("processInstanceKey")))
                .findFirst()
                .ifPresent(entry -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> initVars = entry.get("variables") instanceof Map<?,?> m
                        ? (Map<String, Object>) m : Map.of();
                    variables.putAll(initVars);
                });

            // 2. Worker output cache — usageSummary, usageScore, reclaimable, vendorQuote, jiraTicket, etc.
            variables.putAll(variableCache.get(piKey));

            // 3. Camunda REST API fallback (catches variables set by engine-native tasks like DMN).
            // processInstanceKey must be sent as a string — the v2 API rejects it as a JSON number.
            try {
                Map<String, Object> varSearch = restHelper.post("/variables/search",
                    Map.of("filter", Map.of("processInstanceKey", String.valueOf(piKey))));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> varItems = (List<Map<String, Object>>) varSearch.getOrDefault("items", List.of());
                for (Map<String, Object> v : varItems) {
                    String name = String.valueOf(v.get("name"));
                    Object rawVal = v.get("value");
                    if (rawVal == null) continue;
                    try { variables.putIfAbsent(name, restHelper.mapper.readValue(String.valueOf(rawVal), Object.class)); }
                    catch (Exception ignored) {}
                }
            } catch (Exception ignored) {
                log.debug("[tasks] variables/search fallback skipped for piKey={}", piKey);
            }

            return ResponseEntity.ok(Map.of("variables", variables));
        } catch (Exception e) {
            log.error("[tasks] context failed for piKey={}: {}", piKey, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /** Complete a user task with the provided variables. */
    @PostMapping("/{taskKey}/complete")
    public ResponseEntity<?> completeTask(@PathVariable long taskKey, @RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> variables = body.get("variables") instanceof Map<?,?> m
                ? (Map<String, Object>) m : Map.of();

            client.newCompleteUserTaskCommand(taskKey)
                .variables(variables)
                .send()
                .join();

            log.info("[tasks] completed task {} vars={}", taskKey, variables.keySet());
            return ResponseEntity.ok(Map.of("completed", true));
        } catch (Exception e) {
            log.error("[tasks] complete failed for {}: {}", taskKey, e.getMessage());
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return ResponseEntity.status(500).body(Map.of("error", msg != null ? msg : e.getClass().getSimpleName()));
        }
    }

    /**
     * Bulk-clears the TL review backlog (e.g. after a round of demo/test runs): completes every
     * open Task_TlReview cluster-wide by accepting that ticket's own AI provisional rating as
     * final, with no override. Paginates /user-tasks/search directly rather than reusing
     * listTasks() above, since that endpoint returns only the first page.
     */
    @PostMapping("/finalize-open-reviews")
    public ResponseEntity<?> finalizeOpenReviews() {
        try {
            // Pages while a *full* page comes back rather than trusting page.hasMoreTotalItems -
            // that flag has been observed to report false with more results still unfetched (see
            // AdminController.fetchVariableValues), which would silently leave a backlog behind.
            int pageLimit = 100;
            List<Map<String, Object>> tasks = new ArrayList<>();
            String after = null;
            while (true) {
                Map<String, Object> page = after == null ? Map.of("limit", pageLimit) : Map.of("limit", pageLimit, "after", after);
                Map<String, Object> res = restHelper.post("/user-tasks/search",
                    Map.of("filter", Map.of("state", "CREATED", "elementId", "Task_TlReview"), "page", page));

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) res.getOrDefault("items", List.of());
                tasks.addAll(items);

                if (items.size() < pageLimit) break;
                @SuppressWarnings("unchecked")
                Map<String, Object> pageInfo = (Map<String, Object>) res.getOrDefault("page", Map.of());
                after = String.valueOf(pageInfo.get("endCursor"));
            }

            int finalized = 0;
            List<Map<String, Object>> failures = new ArrayList<>();
            for (Map<String, Object> task : tasks) {
                long taskKey = asLong(task.get("userTaskKey"));
                long piKey = asLong(task.get("processInstanceKey"));
                try {
                    Map<String, Object> varRes = restHelper.post("/variables/search", Map.of("filter",
                        Map.of("processInstanceKey", String.valueOf(piKey), "name", "overallProvisionalRating")));
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> varItems = (List<Map<String, Object>>) varRes.getOrDefault("items", List.of());
                    String provisionalRating = "Meets";
                    if (!varItems.isEmpty() && varItems.get(0).get("value") != null) {
                        provisionalRating = String.valueOf(
                            restHelper.mapper.readValue(String.valueOf(varItems.get(0).get("value")), Object.class));
                    }

                    Map<String, Object> completeVars = Map.of(
                        "finalRating", provisionalRating,
                        "reviewedBy", "demo-auto",
                        "coachingNotes", "Auto-finalized, no override"
                    );
                    client.newCompleteUserTaskCommand(taskKey).variables(completeVars).send().join();
                    finalized++;
                } catch (Exception e) {
                    String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    failures.add(Map.of("taskKey", taskKey, "piKey", piKey, "error", msg != null ? msg : e.getClass().getSimpleName()));
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalOpenTasks", tasks.size());
            result.put("finalized", finalized);
            result.put("failed", failures.size());
            result.put("failures", failures);
            log.info("[tasks] finalize-open-reviews total={} finalized={} failed={}", tasks.size(), finalized, failures.size());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[tasks] finalize-open-reviews failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private static long asLong(Object raw) {
        if (raw instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(raw)); }
        catch (Exception e) { return 0L; }
    }
}
