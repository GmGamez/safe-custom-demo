package com.camunda.loanoriginationmortgage;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock job workers for the Epic Games license renewal orchestration demo.
 * Replace each worker body with real integration calls as the demo matures.
 */
@Component
@Profile("!test")
public class MockJobWorkers {

    private static final Logger log = LoggerFactory.getLogger(MockJobWorkers.class);

    // ── Signal aggregation ───────────────────────────────────────────────────

    @JobWorker(type = "fetch_okta_usage")
    public Map<String, Object> fetchOktaUsage(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String appId = String.valueOf(vars.getOrDefault("appId", "unknown"));
        log.info("[mock] fetch_okta_usage appId={} piKey={}", appId, job.getProcessInstanceKey());
        // Mock: return last-login dates per user seat
        return Map.of("oktaUsage", Map.of("activeUsers", 42, "inactiveUsers", 8, "source", "okta"));
    }

    @JobWorker(type = "fetch_flexera_usage")
    public Map<String, Object> fetchFlexeraUsage(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String appId = String.valueOf(vars.getOrDefault("appId", "unknown"));
        log.info("[mock] fetch_flexera_usage appId={} piKey={}", appId, job.getProcessInstanceKey());
        // Mock: return device-level metering data
        return Map.of("flexeraUsage", Map.of("activeDevices", 38, "unmappedDevices", 4, "source", "flexera"));
    }

    @JobWorker(type = "fetch_vendor_quote")
    public Map<String, Object> fetchVendorQuote(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String vendor = String.valueOf(vars.getOrDefault("vendor", "unknown"));
        int seats = ((Number) vars.getOrDefault("requestedSeats", 50)).intValue();
        log.info("[mock] fetch_vendor_quote vendor={} seats={} piKey={}", vendor, seats, job.getProcessInstanceKey());
        return Map.of("vendorQuote", Map.of(
                "vendor", vendor,
                "seats", seats,
                "pricePerSeat", 125.00,
                "totalPrice", seats * 125.00,
                "currency", "USD"
        ));
    }

    // ── License reclamation ──────────────────────────────────────────────────

    @JobWorker(type = "identify_reclaimable_licenses")
    public Map<String, Object> identifyReclaimable(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        log.info("[mock] identify_reclaimable_licenses piKey={}", job.getProcessInstanceKey());
        // Mock: users inactive ≥ 60 days or departed
        return Map.of("reclaimable", List.of(
                Map.of("userId", "u001", "reason", "departed",      "lastLogin", "2025-01-10"),
                Map.of("userId", "u002", "reason", "inactive_90d",  "lastLogin", "2025-04-05"),
                Map.of("userId", "u003", "reason", "inactive_60d",  "lastLogin", "2025-05-01")
        ), "reclaimableCount", 3);
    }

    @JobWorker(type = "reclaim_licenses")
    public Map<String, Object> reclaimLicenses(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        @SuppressWarnings("unchecked")
        List<?> reclaimable = (List<?>) vars.getOrDefault("approvedReclaims", List.of());
        log.info("[mock] reclaim_licenses count={} piKey={}", reclaimable.size(), job.getProcessInstanceKey());
        return Map.of("reclaimedCount", reclaimable.size(), "reclaimStatus", "success");
    }

    // ── External system integrations ─────────────────────────────────────────

    @JobWorker(type = "create_jira_legal_ticket")
    public Map<String, Object> createJiraLegalTicket(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String appName = String.valueOf(vars.getOrDefault("appName", "unknown"));
        log.info("[mock] create_jira_legal_ticket app={} piKey={}", appName, job.getProcessInstanceKey());
        return Map.of("jiraTicket", Map.of("key", "LEGAL-" + (job.getProcessInstanceKey() % 10000),
                "status", "Open", "app", appName));
    }

    @JobWorker(type = "submit_coupa_po")
    public Map<String, Object> submitCoupaPO(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        log.info("[mock] submit_coupa_po piKey={}", job.getProcessInstanceKey());
        Map<String, Object> quote = vars.get("vendorQuote") instanceof Map<?,?> m
                ? (Map<String, Object>) m : Map.of();
        return Map.of("coupaPO", Map.of(
                "poNumber", "PO-" + (job.getProcessInstanceKey() % 100000),
                "status", "Submitted",
                "amount", quote.getOrDefault("totalPrice", 0)
        ));
    }

    @JobWorker(type = "notify_app_owner")
    public void notifyAppOwner(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String appOwner = String.valueOf(vars.getOrDefault("appOwner", "unknown"));
        log.info("[mock] notify_app_owner owner={} piKey={}", appOwner, job.getProcessInstanceKey());
    }

    // ── Decision support ─────────────────────────────────────────────────────

    @JobWorker(type = "aggregate_usage_signals")
    public Map<String, Object> aggregateUsageSignals(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String demoOutcome = String.valueOf(vars.getOrDefault("demo_outcome", ""));
        int seats = ((Number) vars.getOrDefault("requestedSeats", 50)).intValue();

        int resolved;
        boolean conflict;

        // demo_outcome drives the utilization ratio so every scenario hits a distinct DMN path
        switch (demoOutcome) {
            case "renew"    -> { resolved = (int)(seats * 0.92); conflict = false; }  // ≥ 0.85 → renew_as_is
            case "reduce"   -> { resolved = (int)(seats * 0.72); conflict = false; }  // 0.60-0.85 → reduce_seats
            case "reclaim"  -> { resolved = (int)(seats * 0.40); conflict = false; }  // < 0.60 → reclaim_and_reduce
            case "conflict" -> { resolved = (int)(seats * 0.65); conflict = true;  }  // conflict → escalate_to_human
            default -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> okta    = vars.get("oktaUsage")    instanceof Map<?,?> m ? (Map<String,Object>) m : Map.of();
                @SuppressWarnings("unchecked")
                Map<String, Object> flexera = vars.get("flexeraUsage") instanceof Map<?,?> m ? (Map<String,Object>) m : Map.of();
                int activeOkta    = ((Number) okta.getOrDefault("activeUsers",    0)).intValue();
                int activeFlexera = ((Number) flexera.getOrDefault("activeDevices", 0)).intValue();
                resolved = Math.max(activeOkta, activeFlexera);
                // flag conflict only when divergence exceeds 10% of resolved count
                conflict = activeFlexera > 0 && Math.abs(activeOkta - activeFlexera) > resolved * 0.10;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("resolvedActiveUsers", resolved);
        summary.put("oktaActive",    (int)(resolved * 0.90));
        summary.put("flexeraActive", (int)(resolved * 0.95));
        summary.put("conflictDetected", conflict);

        log.info("[mock] aggregate_usage_signals outcome={} resolved={}/{} conflict={} piKey={}",
                demoOutcome.isEmpty() ? "derived" : demoOutcome, resolved, seats, conflict, job.getProcessInstanceKey());
        return Map.of("usageSummary", summary);
    }
}
