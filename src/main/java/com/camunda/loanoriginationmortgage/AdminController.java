package com.camunda.loanoriginationmortgage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

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
     * Human-review rate across every SupportQaReview ticket that has reached the selection
     * engine, tracking the trial's 5-8% target. Reads directly from Zeebe's variables/search API
     * (every "selectionChannel" variable on the cluster - apply-sample-selection is the only
     * worker that ever writes one, so no other demo on this shared cluster can collide) rather
     * than the in-memory session cache, so the count survives app restarts across many test runs.
     */
    @GetMapping("/review-rate-report")
    public ResponseEntity<?> getReviewRateReport() {
        try {
            List<String> channels = fetchVariableValues("selectionChannel").stream()
                .map(String::valueOf).toList();

            Map<String, Long> byChannel = channels.stream()
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));

            long total = channels.size();
            long notReviewed = byChannel.getOrDefault("None", 0L);
            long routedToReview = total - notReviewed;
            double reviewRatePercent = total > 0 ? Math.round((routedToReview * 1000.0) / total) / 10.0 : 0.0;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalTickets", total);
            result.put("routedToReview", routedToReview);
            result.put("notReviewed", notReviewed);
            result.put("reviewRatePercent", reviewRatePercent);
            result.put("byChannel", byChannel);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[admin] review-rate-report failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Avg QA score/trend, rating mix, per-category averages, and anomaly rate across every scored
     * SupportQaReview ticket — the "avg QA score, trends" line item the weekly Support Brief
     * (context/Safe Software - Camunda Proposal - Support_QA_Process_Overview.pdf) calls for.
     */
    @GetMapping("/quality-trend-report")
    public ResponseEntity<?> getQualityTrendReport() {
        try {
            List<Object> scores = fetchVariableValues("overallScorePercent");
            List<Object> ratings = fetchVariableValues("overallProvisionalRating");
            List<Object> anomalies = fetchVariableValues("anomalyFlagged");

            DoubleSummaryStatistics scoreStats = scores.stream()
                .filter(v -> v instanceof Number)
                .mapToDouble(v -> ((Number) v).doubleValue())
                .summaryStatistics();

            Map<String, Long> ratingDistribution = ratings.stream()
                .map(String::valueOf)
                .collect(Collectors.groupingBy(r -> r, Collectors.counting()));

            long anomalyCount = anomalies.stream().filter(Boolean.TRUE::equals).count();

            Map<String, Object> avgScoreByCategory = new LinkedHashMap<>();
            for (String category : SupportQaWorkers.RUBRIC_CATEGORY_ORDER) {
                DoubleSummaryStatistics categoryStats = fetchVariableValues("score_" + category).stream()
                    .filter(v -> v instanceof Number)
                    .mapToDouble(v -> ((Number) v).doubleValue())
                    .summaryStatistics();
                if (categoryStats.getCount() > 0) {
                    avgScoreByCategory.put(category, Math.round(categoryStats.getAverage() * 10) / 10.0);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ticketsScored", (long) scoreStats.getCount());
            result.put("avgScorePercent", scoreStats.getCount() > 0 ? Math.round(scoreStats.getAverage() * 10) / 10.0 : null);
            result.put("minScorePercent", scoreStats.getCount() > 0 ? scoreStats.getMin() : null);
            result.put("maxScorePercent", scoreStats.getCount() > 0 ? scoreStats.getMax() : null);
            result.put("ratingDistribution", ratingDistribution);
            result.put("anomalyFlaggedCount", anomalyCount);
            result.put("anomalyRatePercent", !scores.isEmpty() ? Math.round((anomalyCount * 1000.0) / scores.size()) / 10.0 : 0.0);
            result.put("avgScoreByCategory", avgScoreByCategory);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[admin] quality-trend-report failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Model-call/token totals across every scored ticket — the "measure and control model calls,
     * token use... and infrastructure costs" trial-evaluation goal, made visible as a running total
     * instead of only per-ticket (SupportQaWorkers.determineOverallScore already computes the
     * per-ticket totalModelCalls/totalInputTokens/totalOutputTokens from each dimension agent).
     */
    @GetMapping("/cost-report")
    public ResponseEntity<?> getCostReport() {
        try {
            List<Object> modelCalls = fetchVariableValues("totalModelCalls");
            List<Object> inputTokens = fetchVariableValues("totalInputTokens");
            List<Object> outputTokens = fetchVariableValues("totalOutputTokens");

            long ticketsScored = modelCalls.size();
            long sumModelCalls = sumLong(modelCalls);
            long sumInputTokens = sumLong(inputTokens);
            long sumOutputTokens = sumLong(outputTokens);
            long sumTotalTokens = sumInputTokens + sumOutputTokens;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ticketsScored", ticketsScored);
            result.put("totalModelCalls", sumModelCalls);
            result.put("totalInputTokens", sumInputTokens);
            result.put("totalOutputTokens", sumOutputTokens);
            result.put("totalTokens", sumTotalTokens);
            result.put("avgModelCallsPerTicket", ticketsScored > 0 ? Math.round((sumModelCalls * 10.0) / ticketsScored) / 10.0 : 0.0);
            result.put("avgTokensPerTicket", ticketsScored > 0 ? Math.round((sumTotalTokens * 10.0) / ticketsScored) / 10.0 : 0.0);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[admin] cost-report failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * TL override rate against the AI's provisional rating, across every ticket that reached
     * record-qa-result — the calibration-loop signal (AI-vs-human agreement / scoring drift) the
     * process overview's "calibration loop" step is meant to feed.
     */
    @GetMapping("/calibration-report")
    public ResponseEntity<?> getCalibrationReport() {
        try {
            List<Object> overridden = fetchVariableValues("overridden");
            List<Object> sources = fetchVariableValues("finalRatingSource");
            List<Object> effectiveRatings = fetchVariableValues("effectiveRating");

            long recorded = sources.size();
            long humanReviewed = sources.stream().filter(v -> "human".equals(String.valueOf(v))).count();
            long aiUnreviewed = recorded - humanReviewed;
            long overrideCount = overridden.stream().filter(Boolean.TRUE::equals).count();
            double overrideRatePercent = humanReviewed > 0 ? Math.round((overrideCount * 1000.0) / humanReviewed) / 10.0 : 0.0;

            Map<String, Long> effectiveRatingDistribution = effectiveRatings.stream()
                .map(String::valueOf)
                .collect(Collectors.groupingBy(r -> r, Collectors.counting()));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("recordedTickets", recorded);
            result.put("humanReviewed", humanReviewed);
            result.put("aiUnreviewed", aiUnreviewed);
            result.put("overrideCount", overrideCount);
            result.put("overrideRatePercent", overrideRatePercent);
            result.put("effectiveRatingDistribution", effectiveRatingDistribution);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[admin] calibration-report failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private static long sumLong(List<Object> values) {
        return values.stream().filter(v -> v instanceof Number).mapToLong(v -> ((Number) v).longValue()).sum();
    }

    private static final int VARIABLE_SEARCH_PAGE_LIMIT = 100;

    /**
     * Every value ever written cluster-wide for a given variable name, paged through
     * /variables/search. Only one worker in this demo ever writes each of the names these reports
     * query, so this is safe on the shared trial cluster without filtering by process id.
     *
     * Pages while a *full* page comes back, rather than trusting page.hasMoreTotalItems - observed
     * that flag return false on a query with 145 total matches after only the first 100 had been
     * returned, which silently truncated every report once the cluster passed 100 scored tickets.
     *
     * Also keeps only each variable's root-process-instance copy (processInstanceKey ==
     * rootProcessInstanceKey). Variables written inside determine-overall-score run at the
     * TicketScoring call-activity scope, then propagateAllChildVariables copies them up onto the
     * root SupportQaReview instance too - a name-only search with no scope filter returns both
     * copies, silently doubling every report built on those variables (overallScorePercent,
     * totalTokens, score_*, etc.). Variables written directly in the root process (selectionChannel,
     * finalRatingSource, ...) only ever have the one root copy, so this filter is a no-op for them.
     */
    private List<Object> fetchVariableValues(String name) {
        List<Object> values = new ArrayList<>();
        String after = null;

        while (true) {
            Map<String, Object> page = after == null
                ? Map.of("limit", VARIABLE_SEARCH_PAGE_LIMIT)
                : Map.of("limit", VARIABLE_SEARCH_PAGE_LIMIT, "after", after);
            Map<String, Object> res = restHelper.post("/variables/search",
                Map.of("filter", Map.of("name", name), "page", page));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) res.getOrDefault("items", List.of());
            for (Map<String, Object> v : items) {
                Object piKey = v.get("processInstanceKey");
                Object rootPiKey = v.get("rootProcessInstanceKey");
                if (piKey != null && rootPiKey != null && !piKey.equals(rootPiKey)) continue;
                Object rawVal = v.get("value");
                if (rawVal == null) continue;
                try {
                    values.add(restHelper.mapper.readValue(String.valueOf(rawVal), Object.class));
                } catch (Exception ignored) {}
            }

            if (items.size() < VARIABLE_SEARCH_PAGE_LIMIT) break;
            @SuppressWarnings("unchecked")
            Map<String, Object> pageInfo = (Map<String, Object>) res.getOrDefault("page", Map.of());
            after = String.valueOf(pageInfo.get("endCursor"));
        }
        return values;
    }

}
