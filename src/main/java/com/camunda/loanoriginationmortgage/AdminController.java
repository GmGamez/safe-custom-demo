package com.camunda.loanoriginationmortgage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Value("${camunda.client.cloud.cluster-id}")
    private String clusterId;

    @Value("${camunda.client.cloud.region}")
    private String region;

    private final CamundaRestHelper restHelper;

    public AdminController(CamundaRestHelper restHelper) {
        this.restHelper = restHelper;
    }

    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        return ResponseEntity.ok(Map.of(
            "operateBaseUrl",  "https://" + region + ".operate.camunda.io/"  + clusterId,
            "tasklistBaseUrl", "https://" + region + ".tasklist.camunda.io/" + clusterId
        ));
    }

    /**
     * Returns the current active element ID (task/gateway) for a process instance.
     * Used by the admin dashboard to show where each instance is in the flow.
     */
    @GetMapping("/instance-step/{piKey}")
    public ResponseEntity<?> getInstanceStep(@PathVariable long piKey) {
        try {
            // processInstanceKey must be sent as a string — the v2 API rejects it as a JSON number
            // ("Request property [filter.processInstanceKey] cannot be parsed").
            Map<String, Object> res = restHelper.post("/element-instances/search",
                Map.of("filter", Map.of("processInstanceKey", String.valueOf(piKey), "state", "ACTIVE")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) res.get("items");

            if (items == null || items.isEmpty()) {
                return ResponseEntity.ok(Map.of("currentStep", "", "state", "completed"));
            }

            Set<String> leafTypes = Set.of("USER_TASK", "SERVICE_TASK", "BUSINESS_RULE_TASK", "SCRIPT_TASK", "SEND_TASK", "RECEIVE_TASK");

            List<Map<String, Object>> candidates = items.stream()
                .filter(el -> {
                    String id = String.valueOf(el.getOrDefault("elementId", ""));
                    return !id.isEmpty() && !id.startsWith("StartEvent") && !id.startsWith("EndEvent");
                })
                .toList();

            // Prefer the innermost active leaf task (e.g. a user task inside a sub-process)
            // over the containing sub-process element itself.
            String step = candidates.stream()
                .filter(el -> leafTypes.contains(String.valueOf(el.getOrDefault("type", ""))))
                .map(el -> String.valueOf(el.getOrDefault("elementId", "")))
                .findFirst()
                .or(() -> candidates.stream().map(el -> String.valueOf(el.getOrDefault("elementId", ""))).findFirst())
                .orElse("");

            return ResponseEntity.ok(Map.of("currentStep", step, "state", "active"));
        } catch (Exception e) {
            log.debug("[admin] step query failed for piKey={}: {}", piKey, e.getMessage());
            return ResponseEntity.ok(Map.of("currentStep", "", "state", "unknown"));
        }
    }

    /**
     * Full traceability record for a process instance: every element it has passed through
     * (task, gateway, event, agent tool call) with timestamps and the variables set at that
     * point, plus assignee/completion info for user tasks. This is the same underlying data
     * Operate shows, reassembled into a single timeline for the app's own Audit Trail view.
     */
    @GetMapping("/audit-trail/{piKey}")
    public ResponseEntity<?> getAuditTrail(@PathVariable long piKey) {
        try {
            // processInstanceKey must be sent as a string — the v2 API rejects it as a JSON number.
            String piKeyStr = String.valueOf(piKey);

            Map<String, Object> elementRes = restHelper.post("/element-instances/search", Map.of(
                "filter", Map.of("processInstanceKey", piKeyStr),
                "sort", List.of(Map.of("field", "elementInstanceKey", "order", "ASC")),
                "page", Map.of("limit", 500)
            ));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> elements = (List<Map<String, Object>>) elementRes.getOrDefault("items", List.of());

            Map<String, Object> variableRes = restHelper.post("/variables/search", Map.of(
                "filter", Map.of("processInstanceKey", piKeyStr),
                "page", Map.of("limit", 1000)
            ));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> variableItems = (List<Map<String, Object>>) variableRes.getOrDefault("items", List.of());

            Map<String, List<Map<String, Object>>> variablesByScope = new HashMap<>();
            for (Map<String, Object> v : variableItems) {
                String scope = String.valueOf(v.get("scopeKey"));
                variablesByScope.computeIfAbsent(scope, k -> new ArrayList<>())
                    .add(Map.of("name", v.get("name"), "value", parseVariableValue(v.get("value"))));
            }

            Map<String, Object> taskRes = restHelper.post("/user-tasks/search", Map.of(
                "filter", Map.of("processInstanceKey", piKeyStr),
                "page", Map.of("limit", 200)
            ));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> userTasks = (List<Map<String, Object>>) taskRes.getOrDefault("items", List.of());
            Map<String, Map<String, Object>> taskByElementInstance = new HashMap<>();
            for (Map<String, Object> t : userTasks) {
                taskByElementInstance.put(String.valueOf(t.get("elementInstanceKey")), t);
            }

            List<Map<String, Object>> timeline = new ArrayList<>();
            for (Map<String, Object> el : elements) {
                String elementInstanceKey = String.valueOf(el.get("elementInstanceKey"));
                String elementId = String.valueOf(el.getOrDefault("elementId", ""));

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("elementInstanceKey", elementInstanceKey);
                entry.put("elementId", elementId);
                entry.put("elementName", el.get("elementName"));
                entry.put("type", el.get("type"));
                entry.put("state", el.get("state"));
                entry.put("startDate", el.get("startDate"));
                entry.put("endDate", el.get("endDate"));
                entry.put("isAgent", "AdHocSubProcess_TriageAgent".equals(elementId));
                entry.put("isAgentTool", elementId.startsWith("Tool_"));
                entry.put("variablesSet", variablesByScope.getOrDefault(elementInstanceKey, List.of()));

                Map<String, Object> task = taskByElementInstance.get(elementInstanceKey);
                if (task != null) {
                    entry.put("assignee", task.get("assignee"));
                    entry.put("candidateGroups", task.get("candidateGroups"));
                    entry.put("completionDate", task.get("completionDate"));
                }
                timeline.add(entry);
            }

            return ResponseEntity.ok(Map.of("processInstanceKey", piKey, "timeline", timeline));
        } catch (Exception e) {
            log.error("[admin] audit-trail failed for piKey={}: {}", piKey, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /** Variable values come back double-serialized (e.g. a string is `"\"MEDIUM\""`) — re-parse to the real value. */
    private Object parseVariableValue(Object raw) {
        if (raw == null) return null;
        try {
            return restHelper.mapper.readValue(String.valueOf(raw), Object.class);
        } catch (Exception e) {
            return raw;
        }
    }
}
