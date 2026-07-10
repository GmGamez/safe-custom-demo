package com.camunda.loanoriginationmortgage;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
@Profile("!test")
public class MockJobWorkers {

    private static final Logger log = LoggerFactory.getLogger(MockJobWorkers.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ProcessVariableCache cache;

    public MockJobWorkers(ProcessVariableCache cache) {
        this.cache = cache;
    }

    // ── Signal aggregation ───────────────────────────────────────────────────

    @JobWorker(type = "fetch_okta_usage")
    public Map<String, Object> fetchOktaUsage(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        int seats = seats(vars);
        String outcome = outcome(vars);

        int active = switch (outcome) {
            case "renew"    -> (int)(seats * 0.92);
            case "reduce"   -> (int)(seats * 0.72);
            case "reclaim"  -> (int)(seats * 0.42);
            case "conflict" -> (int)(seats * 0.65);
            default         -> (int)(seats * 0.70);
        };
        int inactive = seats - active;

        Map<String, Object> result = Map.of("oktaUsage", Map.of(
            "activeUsers",   active,
            "inactiveUsers", inactive,
            "source",        "Okta Identity Cloud"
        ));
        cache.merge(job.getProcessInstanceKey(), result);
        log.info("[mock] fetch_okta_usage outcome={} active={}/{}", outcome, active, seats);
        return result;
    }

    @JobWorker(type = "fetch_flexera_usage")
    public Map<String, Object> fetchFlexeraUsage(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        int seats = seats(vars);
        String outcome = outcome(vars);

        // Conflict scenario: Flexera diverges significantly (build agents counted as seats)
        int active = switch (outcome) {
            case "renew"    -> (int)(seats * 0.89);
            case "reduce"   -> (int)(seats * 0.70);
            case "reclaim"  -> (int)(seats * 0.38);
            case "conflict" -> (int)(seats * 0.43); // big divergence from Okta
            default         -> (int)(seats * 0.68);
        };
        int unmapped = switch (outcome) {
            case "conflict" -> (int)(seats * 0.15); // shared build machines
            default         -> (int)(seats * 0.04);
        };

        Map<String, Object> result = Map.of("flexeraUsage", Map.of(
            "activeDevices",   active,
            "unmappedDevices", unmapped,
            "source",          "Flexera One"
        ));
        cache.merge(job.getProcessInstanceKey(), result);
        log.info("[mock] fetch_flexera_usage outcome={} active={}/{}", outcome, active, seats);
        return result;
    }

    @JobWorker(type = "fetch_vendor_quote")
    public Map<String, Object> fetchVendorQuote(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String vendor  = String.valueOf(vars.getOrDefault("vendor",  "Unknown Vendor"));
        String appName = String.valueOf(vars.getOrDefault("appName", "Unknown App"));
        int seats = seats(vars);

        // Price per seat varies by product type
        double pricePerSeat = priceForApp(appName);
        double total = seats * pricePerSeat;

        Map<String, Object> result = Map.of("vendorQuote", Map.of(
            "vendor",       vendor,
            "appName",      appName,
            "seats",        seats,
            "pricePerSeat", pricePerSeat,
            "totalPrice",   total,
            "currency",     "USD",
            "quoteRef",     "Q-" + (job.getProcessInstanceKey() % 100000)
        ));
        cache.merge(job.getProcessInstanceKey(), result);
        log.info("[mock] fetch_vendor_quote vendor={} seats={} total=${}", vendor, seats, total);
        return result;
    }

    // ── Usage signal aggregation & scoring ───────────────────────────────────

    @JobWorker(type = "aggregate_usage_signals")
    public Map<String, Object> aggregateUsageSignals(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String outcome = outcome(vars);
        int seats = seats(vars);

        int resolved;
        boolean conflict;

        switch (outcome) {
            case "renew"    -> { resolved = (int)(seats * 0.92); conflict = false; }
            case "reduce"   -> { resolved = (int)(seats * 0.72); conflict = false; }
            case "reclaim"  -> { resolved = (int)(seats * 0.40); conflict = false; }
            case "conflict" -> { resolved = (int)(seats * 0.65); conflict = true;  }
            default -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> okta    = vars.get("oktaUsage")    instanceof Map<?,?> m ? (Map<String,Object>) m : Map.of();
                @SuppressWarnings("unchecked")
                Map<String, Object> flexera = vars.get("flexeraUsage") instanceof Map<?,?> m ? (Map<String,Object>) m : Map.of();
                int oActive = num(okta.getOrDefault("activeUsers",    0));
                int fActive = num(flexera.getOrDefault("activeDevices", 0));
                resolved = Math.max(oActive, fActive);
                conflict  = fActive > 0 && Math.abs(oActive - fActive) > resolved * 0.10;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("resolvedActiveUsers", resolved);
        summary.put("oktaActive",          (int)(resolved * 0.92));
        summary.put("flexeraActive",       conflict ? (int)(resolved * 0.66) : (int)(resolved * 0.97));
        summary.put("conflictDetected",    conflict);
        summary.put("totalSeats",          seats);

        // Mirror the DMN logic so the cache always has usageScore alongside usageSummary
        double ratio = seats > 0 ? (double) resolved / seats : 0;
        String rec, band;
        if (conflict)        { rec = "escalate_to_human"; band = "conflict"; }
        else if (ratio >= 0.85) { rec = "renew_as_is";      band = "high";     }
        else if (ratio >= 0.60) { rec = "reduce_seats";     band = "medium";   }
        else                    { rec = "reclaim_and_reduce"; band = "low";     }

        Map<String, Object> score = Map.of(
            "recommendation",  rec,
            "utilizationBand", band,
            "utilizationPct",  Math.round(ratio * 100) + "%"
        );

        Map<String, Object> result = Map.of("usageSummary", summary, "usageScore", score);
        cache.merge(job.getProcessInstanceKey(), result);
        log.info("[mock] aggregate_usage_signals outcome={} resolved={}/{} conflict={} rec={}",
                outcome.isEmpty() ? "derived" : outcome, resolved, seats, conflict, rec);
        return result;
    }

    // ── License reclamation ──────────────────────────────────────────────────

    @JobWorker(type = "identify_reclaimable_licenses")
    public Map<String, Object> identifyReclaimable(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String outcome   = outcome(vars);
        String appOwner  = String.valueOf(vars.getOrDefault("appOwner", "owner@epicgames.com"));
        String domain    = emailDomain(appOwner);

        // Candidate pool — realistic Epic Games IT persona names
        String[][] pool = {
            { "sarah.chen",       "departed",    daysAgo(185) },
            { "marcus.rodriguez", "inactive_90d", daysAgo(105) },
            { "aisha.patel",      "inactive_60d", daysAgo(73)  },
            { "james.okonkwo",    "departed",     daysAgo(210) },
            { "emily.nguyen",     "inactive_90d", daysAgo(98)  },
            { "david.kowalski",   "inactive_60d", daysAgo(67)  },
            { "priya.sharma",     "departed",     daysAgo(156) },
            { "alex.campbell",    "inactive_90d", daysAgo(112) },
        };

        int count = switch (outcome) {
            case "reclaim"  -> 6;
            case "reduce"   -> 3;
            case "renew"    -> 1;
            case "conflict" -> 3;
            default         -> 3;
        };
        count = Math.min(count, pool.length);

        List<Map<String, Object>> reclaimable = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            reclaimable.add(Map.of(
                "userId",    pool[i][0] + "@" + domain,
                "reason",    pool[i][1],
                "lastLogin", pool[i][2]
            ));
        }

        Map<String, Object> result = Map.of(
            "reclaimable",      reclaimable,
            "reclaimableCount", reclaimable.size()
        );
        cache.merge(job.getProcessInstanceKey(), result);
        log.info("[mock] identify_reclaimable_licenses count={} piKey={}", reclaimable.size(), job.getProcessInstanceKey());
        return result;
    }

    @JobWorker(type = "reclaim_licenses")
    public Map<String, Object> reclaimLicenses(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        @SuppressWarnings("unchecked")
        List<?> approved = vars.get("approvedReclaims") instanceof List<?> l ? l : List.of();
        int count = approved.size();
        Map<String, Object> result = Map.of("reclaimedCount", count, "reclaimStatus", "success");
        cache.merge(job.getProcessInstanceKey(), result);
        log.info("[mock] reclaim_licenses count={} piKey={}", count, job.getProcessInstanceKey());
        return result;
    }

    // ── External system integrations ─────────────────────────────────────────

    @JobWorker(type = "create_jira_legal_ticket")
    public Map<String, Object> createJiraLegalTicket(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String appName = String.valueOf(vars.getOrDefault("appName", "Unknown App"));
        String vendor  = String.valueOf(vars.getOrDefault("vendor",  "Unknown Vendor"));
        String ticketKey = "LEGAL-" + (1000 + job.getProcessInstanceKey() % 9000);
        Map<String, Object> result = Map.of("jiraTicket", Map.of(
            "key",     ticketKey,
            "status",  "Open",
            "summary", "License amendment — " + appName + " (" + vendor + ")",
            "app",     appName
        ));
        cache.merge(job.getProcessInstanceKey(), result);
        log.info("[mock] create_jira_legal_ticket key={} app={}", ticketKey, appName);
        return result;
    }

    @JobWorker(type = "submit_coupa_po")
    public Map<String, Object> submitCoupaPO(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> quote = vars.get("vendorQuote") instanceof Map<?,?> m ? (Map<String,Object>) m : Map.of();
        int finalSeats  = vars.get("finalSeats") instanceof Number n ? n.intValue() : num(quote.getOrDefault("seats", 0));
        double price    = quote.get("pricePerSeat") instanceof Number n ? n.doubleValue() : 125.0;
        String poNumber = "PO-" + (10000 + job.getProcessInstanceKey() % 90000);
        Map<String, Object> result = Map.of("coupaPO", Map.of(
            "poNumber", poNumber,
            "status",   "Submitted",
            "seats",    finalSeats,
            "amount",   finalSeats * price
        ));
        cache.merge(job.getProcessInstanceKey(), result);
        log.info("[mock] submit_coupa_po po={} seats={}", poNumber, finalSeats);
        return result;
    }

    @JobWorker(type = "notify_app_owner")
    public void notifyAppOwner(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String appOwner = String.valueOf(vars.getOrDefault("appOwner", "unknown"));
        log.info("[mock] notify_app_owner owner={} piKey={}", appOwner, job.getProcessInstanceKey());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String outcome(Map<String, Object> vars) {
        return String.valueOf(vars.getOrDefault("demo_outcome", ""));
    }

    private static int seats(Map<String, Object> vars) {
        return num(vars.getOrDefault("requestedSeats", 50));
    }

    private static int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private static String daysAgo(int days) {
        return LocalDate.now().minusDays(days).format(DATE_FMT);
    }

    private static String emailDomain(String email) {
        int at = email.indexOf('@');
        return at >= 0 ? email.substring(at + 1) : "epicgames.com";
    }

    private static double priceForApp(String appName) {
        String lower = appName.toLowerCase();
        if (lower.contains("github") || lower.contains("azure") || lower.contains("microsoft")) return 180.0;
        if (lower.contains("salesforce") || lower.contains("tableau"))                          return 210.0;
        if (lower.contains("adobe") || lower.contains("figma"))                                  return 115.0;
        if (lower.contains("slack"))                                                             return  95.0;
        if (lower.contains("zoom"))                                                              return  75.0;
        if (lower.contains("jira") || lower.contains("confluence"))                             return  90.0;
        if (lower.contains("datadog") || lower.contains("splunk") || lower.contains("new relic")) return 250.0;
        if (lower.contains("servicenow"))                                                        return 320.0;
        return 125.0;
    }
}
