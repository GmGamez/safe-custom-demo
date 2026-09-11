package com.camunda.loanoriginationmortgage;

import io.camunda.client.CamundaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/api/process")
public class ProcessController {

    private static final Logger log = LoggerFactory.getLogger(ProcessController.class);
    private static final int MAX_RECENT = 50;
    private final CopyOnWriteArrayList<Map<String, Object>> recentInstances = new CopyOnWriteArrayList<>();

    private final CamundaClient client;
    private final CamundaRestHelper restHelper;
    private final ProcessVariableCache variableCache;

    public ProcessController(CamundaClient client, CamundaRestHelper restHelper, ProcessVariableCache variableCache) {
        this.client = client;
        this.restHelper = restHelper;
        this.variableCache = variableCache;
    }

    @PostMapping("/start")
    public ResponseEntity<?> startProcess(@RequestBody StartRequest request) {
        log.info("Starting process '{}' with variables: {}", request.processId(), request.variables());
        try {
            var result = client.newCreateInstanceCommand()
                    .bpmnProcessId(request.processId())
                    .latestVersion()
                    .variables(request.variables())
                    .send()
                    .join();

            log.info("Started process instance {} for '{}'", result.getProcessInstanceKey(), request.processId());

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("processInstanceKey", result.getProcessInstanceKey());
            entry.put("bpmnProcessId", result.getBpmnProcessId());
            entry.put("startedAt", Instant.now().toString());
            entry.put("variables", request.variables());
            recentInstances.add(0, entry);
            if (recentInstances.size() > MAX_RECENT) recentInstances.subList(MAX_RECENT, recentInstances.size()).clear();

            return ResponseEntity.ok(Map.of(
                    "processInstanceKey",   result.getProcessInstanceKey(),
                    "processDefinitionKey", result.getProcessDefinitionKey(),
                    "bpmnProcessId",        result.getBpmnProcessId(),
                    "version",              result.getVersion()
            ));
        } catch (Exception e) {
            String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            if (message == null) message = e.getClass().getSimpleName();
            log.error("Failed to start process '{}': {}", request.processId(), message, e);
            return ResponseEntity.status(500).body(Map.of("error", message));
        }
    }

    @GetMapping("/recent")
    public ResponseEntity<?> getRecent() {
        return ResponseEntity.ok(recentInstances);
    }

    /**
     * Merged variable snapshot for a process instance (no task key required), so dashboards can
     * show ticket context and QA results without needing an open user task. Same three-tier merge
     * TasksController.getTaskContext uses: initial submission vars, job-worker output cache, then
     * the Camunda variables/search API as a fallback.
     */
    @GetMapping("/{piKey}/variables")
    public ResponseEntity<?> getInstanceVariables(@PathVariable long piKey) {
        try {
            Map<String, Object> variables = new LinkedHashMap<>();

            recentInstances.stream()
                .filter(e -> piKey == asLong(e.get("processInstanceKey")))
                .findFirst()
                .ifPresent(entry -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> initVars = entry.get("variables") instanceof Map<?,?> m
                        ? (Map<String, Object>) m : Map.of();
                    variables.putAll(initVars);
                });

            variables.putAll(variableCache.get(piKey));

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
                log.debug("[process] variables/search fallback skipped for piKey={}", piKey);
            }

            return ResponseEntity.ok(Map.of("variables", variables));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    List<Map<String, Object>> getRecentInstancesList() {
        return Collections.unmodifiableList(recentInstances);
    }

    private static long asLong(Object raw) {
        if (raw instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(raw)); }
        catch (Exception e) { return 0L; }
    }

    public record StartRequest(String processId, Map<String, Object> variables) {}
}
