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
    private final CamundaClient client;

    public TasksController(CamundaRestHelper restHelper, ProcessController processController, CamundaClient client) {
        this.restHelper = restHelper;
        this.processController = processController;
        this.client = client;
    }

    /** List open user tasks in the license-renewal process, enriched with in-memory app context. */
    @GetMapping
    public ResponseEntity<?> listTasks() {
        try {
            Map<String, Object> res = restHelper.post("/user-tasks/search",
                Map.of("filter", Map.of("processDefinitionId", "license-renewal", "state", "CREATED")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) res.getOrDefault("items", List.of());

            List<Map<String, Object>> enriched = new ArrayList<>();
            for (Map<String, Object> task : items) {
                Map<String, Object> t = new LinkedHashMap<>(task);
                long piKey = asLong(t.get("processInstanceKey"));
                processController.getRecentInstancesList().stream()
                    .filter(e -> piKey == asLong(e.get("processInstanceKey")))
                    .findFirst()
                    .ifPresent(entry -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> vars = entry.get("variables") instanceof Map<?,?> m
                            ? (Map<String, Object>) m : Map.of();
                        t.put("appName",        vars.get("appName"));
                        t.put("vendor",         vars.get("vendor"));
                        t.put("appOwner",       vars.get("appOwner"));
                        t.put("requestedSeats", vars.get("requestedSeats"));
                    });
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
     * piKey (processInstanceKey) is provided by the frontend from the task-list response,
     * avoiding a second user-task search (the userTaskKey filter is not supported by the v2 API).
     */
    @GetMapping("/{taskKey}/context")
    public ResponseEntity<?> getTaskContext(@PathVariable long taskKey,
                                            @RequestParam long piKey) {
        try {

            Map<String, Object> varSearch = restHelper.post("/variables/search",
                Map.of("filter", Map.of("processInstanceKey", piKey)));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> varItems = (List<Map<String, Object>>) varSearch.getOrDefault("items", List.of());

            Map<String, Object> variables = new LinkedHashMap<>();
            for (Map<String, Object> v : varItems) {
                String name = String.valueOf(v.get("name"));
                Object rawVal = v.get("value");
                if (rawVal == null) continue;
                String raw = String.valueOf(rawVal);
                try { variables.put(name, restHelper.mapper.readValue(raw, Object.class)); }
                catch (Exception ex) { variables.put(name, raw); }
            }

            // Fill initial submission variables not yet in Camunda (e.g. appName, vendor)
            processController.getRecentInstancesList().stream()
                .filter(e -> piKey == asLong(e.get("processInstanceKey")))
                .findFirst()
                .ifPresent(entry -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> initVars = entry.get("variables") instanceof Map<?,?> m
                        ? (Map<String, Object>) m : Map.of();
                    initVars.forEach((k, val) -> variables.putIfAbsent(String.valueOf(k), val));
                });

            return ResponseEntity.ok(Map.of("variables", variables));
        } catch (Exception e) {
            log.error("[tasks] context failed for {}: {}", taskKey, e.getMessage());
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

    private static long asLong(Object raw) {
        if (raw instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(raw)); }
        catch (Exception e) { return 0L; }
    }
}
