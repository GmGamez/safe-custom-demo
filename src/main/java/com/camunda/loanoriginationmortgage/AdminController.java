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
     * Used by the admin dashboard to show where each renewal is in the flow.
     */
    @GetMapping("/instance-step/{piKey}")
    public ResponseEntity<?> getInstanceStep(@PathVariable long piKey) {
        try {
            Map<String, Object> res = restHelper.post("/element-instances/search",
                Map.of("filter", Map.of("processInstanceKey", piKey, "state", "ACTIVE")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) res.get("items");

            if (items == null || items.isEmpty()) {
                return ResponseEntity.ok(Map.of("currentStep", "", "state", "completed"));
            }

            String step = items.stream()
                .map(el -> String.valueOf(el.getOrDefault("elementId", "")))
                .filter(s -> !s.isEmpty() && !s.startsWith("StartEvent") && !s.startsWith("EndEvent"))
                .findFirst()
                .orElse("");

            return ResponseEntity.ok(Map.of("currentStep", step, "state", "active"));
        } catch (Exception e) {
            log.debug("[admin] step query failed for piKey={}: {}", piKey, e.getMessage());
            return ResponseEntity.ok(Map.of("currentStep", "", "state", "unknown"));
        }
    }
}
