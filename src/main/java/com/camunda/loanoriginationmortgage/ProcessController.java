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

    public ProcessController(CamundaClient client) {
        this.client = client;
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
     * Publishes a message to correlate with a running process instance — e.g. the
     * "IncidentReported" message that triggers reassessment for an in-production use case.
     */
    @PostMapping("/message")
    public ResponseEntity<?> publishMessage(@RequestBody PublishMessageRequest request) {
        try {
            var result = client.newPublishMessageCommand()
                    .messageName(request.messageName())
                    .correlationKey(request.correlationKey())
                    .variables(request.variables() != null ? request.variables() : Map.of())
                    .send()
                    .join();

            log.info("Published message '{}' correlationKey={} messageKey={}",
                    request.messageName(), request.correlationKey(), result.getMessageKey());

            return ResponseEntity.ok(Map.of("messageKey", result.getMessageKey()));
        } catch (Exception e) {
            String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            if (message == null) message = e.getClass().getSimpleName();
            log.error("Failed to publish message '{}': {}", request.messageName(), message, e);
            return ResponseEntity.status(500).body(Map.of("error", message));
        }
    }

    List<Map<String, Object>> getRecentInstancesList() {
        return Collections.unmodifiableList(recentInstances);
    }

    public record StartRequest(String processId, Map<String, Object> variables) {}
    public record PublishMessageRequest(String messageName, String correlationKey, Map<String, Object> variables) {}
}
