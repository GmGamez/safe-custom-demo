package com.camunda.loanoriginationmortgage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Mock job workers for the Support QA Review process. Stands in for the real Zendesk/Jira
 * integrations Safe Software would wire up for the trial — swap each method body for a real
 * API call once those are available. Everything is seeded from the ticket id so a repeated run
 * against the same id reproduces the same ticket profile.
 */
@Component
@Profile("!test")
public class SupportQaWorkers {

    private static final Logger log = LoggerFactory.getLogger(SupportQaWorkers.class);

    private final ProcessVariableCache variableCache;

    public SupportQaWorkers(ProcessVariableCache variableCache) {
        this.variableCache = variableCache;
    }

    private static Random seeded(String ticketId, String salt) {
        return new Random((ticketId == null ? "unknown" : ticketId).hashCode() * 31L + salt.hashCode());
    }

    // Real Safe Software ticket in context/ZendeskTicketSample/ (FME Flow 2026.2 native-looping
    // regression, Vaughn <-> Shirley C., filed as FMEFORM-38238) - replayed end to end when a
    // ticket id matching this one is used, so the trial can be demoed against a genuine example
    // instead of only synthetic ones.
    private static final String CANONICAL_TICKET_ID = "12345";

    // ── Ticket snapshot ─────────────────────────────────────────────────────

    @JobWorker(type = "fetch-ticket-snapshot")
    public Map<String, Object> fetchTicketSnapshot(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String ticketId = String.valueOf(vars.get("zendeskTicketId"));

        Map<String, Object> out = CANONICAL_TICKET_ID.equals(ticketId)
            ? canonicalTicketSnapshot()
            : randomTicketSnapshot(ticketId);
        log.info("[mock] fetch-ticket-snapshot ticketId={} piKey={}", ticketId, job.getProcessInstanceKey());
        variableCache.merge(job.getProcessInstanceKey(), out);
        return out;
    }

    private static Map<String, Object> canonicalTicketSnapshot() {
        return Map.ofEntries(
            Map.entry("ticketSubject", "Inconsistent behavior between Form / Flow - Trouble with looping on Flow 2026.2"),
            Map.entry("customerName", "Vaughn"),
            Map.entry("agentName", "Shirley C."),
            Map.entry("channel", "Email"),
            Map.entry("hasCall", true),
            Map.entry("tags", List.of("urgency_high", "impact_significant/large", "jira_escalated",
                "product_fme_flow", "support_call_yes", "call_notes_added_yes", "sentiment__neutral")),
            Map.entry("replyCount", 25),
            Map.entry("ticketCreatedAt", "2026-07-15T16:00:57Z"),
            Map.entry("ticketClosedAt", "2026-08-17T19:29:15Z"),
            Map.entry("threadExcerpt", "Customer reported inconsistent looping behavior between FME Form and FME "
                + "Flow after upgrading to 2026.2 - polling loops meant to gate downstream execution were being "
                + "bypassed in headless/Flow runs. Agent Shirley C. gathered job logs from both environments, held "
                + "a screen-share call, and worked through several rounds of log comparisons with the customer, who "
                + "twice flagged that this was blocking a production workflow. Root cause confirmed as a bug in the "
                + "new Native Looping engine (a published workspace parameter wasn't reaching transformers inside "
                + "the loop and silently fell back to its saved default) and filed as FMEFORM-38238, with three "
                + "workaround options provided (pass the value as an attribute, use a deployment parameter, or move "
                + "the check to after the loop).")
        );
    }

    private static Map<String, Object> randomTicketSnapshot(String ticketId) {
        Random rnd = seeded(ticketId, "snapshot");

        List<String> subjects = List.of(
            "Data import failing silently", "License activation error",
            "Custom transformer crashing on large files", "Slow performance on nightly workbench run",
            "Need help configuring FME Flow schedule", "Export format missing attributes",
            "Native looping behaving differently on Flow vs Form", "Kafka connector dropping messages under load",
            "Workspace runner ignoring published parameter", "REST API upload failing for large uploads"
        );
        List<String> customers = List.of(
            "GeoNorth Utilities", "Meridian County GIS", "Coastal Water Authority",
            "Pinecrest Engineering", "Union Rail Logistics", "Harborview Planning Dept"
        );
        List<String> agents = List.of("Priya N.", "Sam T.", "Jordan K.", "Alex R.", "Morgan L.", "Shirley C.");
        List<String> channels = List.of("Email", "Chat", "Call");
        List<String> allTags = List.of("product_fme_flow", "product_fme_form", "native_looping", "kafka_connector",
            "license", "performance", "transformer", "export", "jira_escalated", "urgency_high",
            "impact_significant/large", "support_call_yes", "sentiment__neutral", "how-to");

        String channel = channels.get(rnd.nextInt(channels.size()));
        boolean hasCall = "Call".equals(channel) || rnd.nextInt(100) < 20;
        int replyCount = 1 + rnd.nextInt(12);
        List<String> tags = allTags.subList(0, 1 + rnd.nextInt(3));

        Instant closedAt = Instant.now();
        Instant createdAt = closedAt.minus(6 + rnd.nextInt(90), ChronoUnit.HOURS);

        return Map.ofEntries(
            Map.entry("ticketSubject", subjects.get(rnd.nextInt(subjects.size()))),
            Map.entry("customerName", customers.get(rnd.nextInt(customers.size()))),
            Map.entry("agentName", agents.get(rnd.nextInt(agents.size()))),
            Map.entry("channel", channel),
            Map.entry("hasCall", hasCall),
            Map.entry("tags", tags),
            Map.entry("replyCount", replyCount),
            Map.entry("ticketCreatedAt", createdAt.toString()),
            Map.entry("ticketClosedAt", closedAt.toString()),
            Map.entry("threadExcerpt", "Customer reported the issue, agent " + agents.get(rnd.nextInt(agents.size()))
                + " requested logs, root cause discussed, resolution or workaround provided over " + replyCount + " replies.")
        );
    }

    // ── Deterministic checks ────────────────────────────────────────────────

    @JobWorker(type = "run-deterministic-checks")
    public Map<String, Object> runDeterministicChecks(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String ticketId = String.valueOf(vars.get("zendeskTicketId"));

        Map<String, Object> out = CANONICAL_TICKET_ID.equals(ticketId)
            ? canonicalDeterministicChecks()
            : randomDeterministicChecks(ticketId);
        log.info("[mock] run-deterministic-checks ticketId={} slaMet={} jiraLinked={} piKey={}",
            ticketId, out.get("slaMet"), out.get("jiraLinked"), job.getProcessInstanceKey());
        variableCache.merge(job.getProcessInstanceKey(), out);
        return out;
    }

    private static Map<String, Object> canonicalDeterministicChecks() {
        // First agent reply 2026-07-16T14:56:57Z, ticket created 2026-07-15T16:00:57Z: ~1376 min.
        int firstResponseMinutes = 1376;
        return Map.ofEntries(
            Map.entry("firstResponseMinutes", firstResponseMinutes),
            Map.entry("slaMet", firstResponseMinutes <= 60),
            Map.entry("responseCadenceFlag", true),
            Map.entry("internalNotesPresent", true),
            Map.entry("followUpCommitmentMade", true),
            // Customer twice flagged being left waiting on a production-blocking issue.
            Map.entry("followUpHonoredOnTime", false),
            Map.entry("ticketInvolvesBug", true),
            Map.entry("jiraLinked", true),
            Map.entry("jiraIssueKey", "FMEFORM-38238"),
            // Confirmed bug, thoroughly documented once filed - but filed ~4 weeks into the
            // ticket, not "promptly" as the rubric's Meets Standard describes.
            Map.entry("jiraBugTicketScore", 2)
        );
    }

    private static Map<String, Object> randomDeterministicChecks(String ticketId) {
        Random rnd = seeded(ticketId, "checks");

        int firstResponseMinutes = 5 + rnd.nextInt(175);
        boolean slaMet = firstResponseMinutes <= 60;
        boolean responseCadenceFlag = rnd.nextInt(100) < 15;
        boolean internalNotesPresent = rnd.nextInt(100) < 85;
        boolean followUpCommitmentMade = rnd.nextInt(100) < 50;
        boolean followUpHonoredOnTime = followUpCommitmentMade && rnd.nextInt(100) < 80;

        boolean ticketInvolvesBug = rnd.nextInt(100) < 40;
        boolean jiraLinked = ticketInvolvesBug && rnd.nextInt(100) < 70;
        String jiraIssueKey = jiraLinked ? "FMEFORM-" + (30000 + rnd.nextInt(9999)) : null;
        Object jiraBugTicketScore = !ticketInvolvesBug ? "NotApplicable" : (jiraLinked ? 4 : 1);

        return Map.ofEntries(
            Map.entry("firstResponseMinutes", firstResponseMinutes),
            Map.entry("slaMet", slaMet),
            Map.entry("responseCadenceFlag", responseCadenceFlag),
            Map.entry("internalNotesPresent", internalNotesPresent),
            Map.entry("followUpCommitmentMade", followUpCommitmentMade),
            Map.entry("followUpHonoredOnTime", followUpHonoredOnTime),
            Map.entry("ticketInvolvesBug", ticketInvolvesBug),
            Map.entry("jiraLinked", jiraLinked),
            Map.entry("jiraIssueKey", jiraIssueKey == null ? "" : jiraIssueKey),
            Map.entry("jiraBugTicketScore", jiraBugTicketScore)
        );
    }

    // ── AI agent tools ──────────────────────────────────────────────────────

    /**
     * The nine-category rubric, shared by the AI agents' get-rubric tool below and
     * TasksController's human-facing GET /api/tasks/rubric endpoint, so the criteria a TL sees
     * while reviewing a ticket is the exact same text the agent scored against - not a second,
     * driftable copy.
     */
    static List<Map<String, Object>> rubricCategories() {
        return List.of(
            Map.of("dimension", "Communication", "category", "toneProfessionalism", "weight", CATEGORY_WEIGHTS.get("toneProfessionalism"),
                "meetsStandard", "Warm, professional, uses the customer's name; acknowledges their specific situation before presenting a solution; appropriate greeting and closing."),
            Map.of("dimension", "Communication", "category", "nextStepsOwnership", "weight", CATEGORY_WEIGHTS.get("nextStepsOwnership"),
                "meetsStandard", "Clearly defines the exact action, who's responsible, and when the customer can expect an update or resolution."),
            Map.of("dimension", "Communication", "category", "callChannelJudgment", "weight", CATEGORY_WEIGHTS.get("callChannelJudgment"),
                "meetsStandard", "Right channel chosen for the situation - a call was offered when the issue was complex, and call notes are captured."),
            Map.of("dimension", "Technical Advice", "category", "rootCauseIdentified", "weight", CATEGORY_WEIGHTS.get("rootCauseIdentified"),
                "meetsStandard", "Correctly identifies and explains the best-supported root cause based on the available evidence, not just the symptom."),
            Map.of("dimension", "Technical Advice", "category", "technicalAccuracy", "weight", CATEGORY_WEIGHTS.get("technicalAccuracy"),
                "meetsStandard", "Accurate, complete, up-to-date guidance, tailored to the customer's level of understanding."),
            Map.of("dimension", "Technical Advice", "category", "completeness", "weight", CATEGORY_WEIGHTS.get("completeness"),
                "meetsStandard", "Every part of the question answered; any workaround is clearly framed as temporary with resolution status communicated."),
            Map.of("dimension", "Process Adherence", "category", "jiraBugTicket", "weight", CATEGORY_WEIGHTS.get("jiraBugTicket"),
                "meetsStandard", "Bug documented promptly with a thorough Jira ticket, linked to the Zendesk case immediately."),
            Map.of("dimension", "Process Adherence", "category", "internalNotes", "weight", CATEGORY_WEIGHTS.get("internalNotes"),
                "meetsStandard", "Notes capture troubleshooting, decisions, next steps, and ownership throughout the ticket, with a closing note on resolution."),
            Map.of("dimension", "Process Adherence", "category", "followUpCommitments", "weight", CATEGORY_WEIGHTS.get("followUpCommitments"),
                "meetsStandard", "Commitments to the customer are honoured within the promised timeframe without them needing to chase.")
        );
    }

    @JobWorker(type = "get-rubric")
    public Map<String, Object> getRubric(ActivatedJob job) {
        log.info("[mock] get-rubric piKey={}", job.getProcessInstanceKey());
        Map<String, Object> result = Map.of(
            "scale", "4-Excellent / 3-Meets Standard / 2-Developing / 1-Does Not Meet (or NotApplicable)",
            "weighting", "Final % = sum(score x weight) / sum(4 x weight) across scored (non-NotApplicable) categories.",
            "categories", rubricCategories()
        );
        return Map.of("toolCallResult", result);
    }

    @JobWorker(type = "lookup-jira-issue")
    public Map<String, Object> lookupJiraIssue(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String jiraIssueKey = String.valueOf(vars.getOrDefault("jiraIssueKey", "")).trim();

        if (jiraIssueKey.isEmpty()) {
            log.info("[mock] lookup-jira-issue no issue key provided, piKey={}", job.getProcessInstanceKey());
            return Map.of("toolCallResult", Map.of("found", false, "message", "No Jira issue linked to this ticket."));
        }
        Random rnd = seeded(jiraIssueKey, "jira");
        List<String> statuses = List.of("Open", "In Progress", "Resolved", "Closed");
        Map<String, Object> result = Map.of(
            "found", true,
            "issueKey", jiraIssueKey,
            "status", statuses.get(rnd.nextInt(statuses.size())),
            "priority", rnd.nextInt(100) < 20 ? "High" : "Medium",
            "summary", "Reproduction and fix tracked for the issue reported in this ticket."
        );
        log.info("[mock] lookup-jira-issue key={} piKey={}", jiraIssueKey, job.getProcessInstanceKey());
        return Map.of("toolCallResult", result);
    }

    /**
     * Where each dimension-scoring agent actually submits its scores - shared across all three
     * dimension processes (Communication/Technical Advice/Process Adherence each have their own
     * Tool_SubmitAssessment_* task using this same job type; the BPMN-level output mapping in each
     * file remaps the generic categoryScores/anomalyFlagged/anomalyReasons into that dimension's
     * own comm/tech/proc-prefixed variables). Makes the scoring act itself a traceable job
     * instance instead of parsed text from the agent's final response.
     */
    @JobWorker(type = "submit-dimension-assessment")
    public Map<String, Object> submitDimensionAssessment(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        Object categoryScores = vars.get("categoryScores");
        List<String> categories = categoryScores instanceof Map<?, ?> m
            ? m.keySet().stream().map(String::valueOf).toList()
            : List.of();
        boolean anomalyFlagged = Boolean.TRUE.equals(vars.get("anomalyFlagged"));

        log.info("[mock] submit-dimension-assessment categories={} anomalyFlagged={} piKey={}",
            categories, anomalyFlagged, job.getProcessInstanceKey());

        Map<String, Object> toolCallResult = Map.of(
            "recorded", true,
            "categoriesRecorded", categories,
            "message", "Recorded " + categories.size() + " categor" + (categories.size() == 1 ? "y" : "ies") + "."
        );
        return Map.of("toolCallResult", toolCallResult);
    }

    // ── Ticket Scoring: combine + persist ───────────────────────────────────
    // Communication/Technical Advice/Process Adherence Evaluation run as three parallel AI-agent
    // calls (see support-qa-review.bpmn); these two steps deterministically combine their output
    // and persist it, matching the "Overall Score Determination" / "Persist Score" tasks in the
    // customer-designed QA Review.bpmn reference model.

    // Package-visible (not private) so AdminController's quality-trend-report can iterate the
    // same nine rubric categories without duplicating the list.
    static final List<String> RUBRIC_CATEGORY_ORDER = List.of(
        "toneProfessionalism", "nextStepsOwnership", "callChannelJudgment",
        "rootCauseIdentified", "technicalAccuracy", "completeness",
        "jiraBugTicket", "internalNotes", "followUpCommitments"
    );

    // Category weights from context/QAScoring.pdf (the TLs' weighted scoring summary) - total
    // weight 18, max 72 points. Communication=7/28 (38.9%), Technical Advice=8/32 (44.4%),
    // Process Adherence=3/12 (16.7%).
    private static final Map<String, Integer> CATEGORY_WEIGHTS = Map.of(
        "toneProfessionalism", 3,
        "nextStepsOwnership", 3,
        "callChannelJudgment", 1,
        "rootCauseIdentified", 3,
        "technicalAccuracy", 3,
        "completeness", 2,
        "jiraBugTicket", 1,
        "internalNotes", 1,
        "followUpCommitments", 1
    );

    private static final ObjectMapper JSON = new ObjectMapper();

    @JobWorker(type = "determine-overall-score")
    public Map<String, Object> determineOverallScore(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();

        // The agent is instructed to respond with pure JSON, but reasoning models frequently
        // preface it with prose anyway, which breaks the connector's own parseJson and leaves
        // {comm,tech,proc}CategoryScores null. Fall back to pulling the JSON object out of the raw
        // response text so a single unruly reply doesn't blank out an entire dimension's scores.
        Map<String, Object> comm = resolveDimensionResult(vars, "comm");
        Map<String, Object> tech = resolveDimensionResult(vars, "tech");
        Map<String, Object> proc = resolveDimensionResult(vars, "proc");

        Map<String, Object> categoryScores = new LinkedHashMap<>();
        mergeCategoryScores(categoryScores, comm.get("categoryScores"));
        mergeCategoryScores(categoryScores, tech.get("categoryScores"));
        mergeCategoryScores(categoryScores, proc.get("categoryScores"));
        // Preserve the rubric's dimension ordering regardless of which agent finished first.
        Map<String, Object> orderedCategoryScores = new LinkedHashMap<>();
        for (String category : RUBRIC_CATEGORY_ORDER) {
            if (categoryScores.containsKey(category)) {
                orderedCategoryScores.put(category, categoryScores.get(category));
            }
        }

        List<Integer> numericScores = new ArrayList<>();
        // Flat, single-category variables so reporting tools that can't group by nested object
        // fields (e.g. Camunda Optimize) can still break scores down per rubric category. Left
        // unset for a given instance when the category is NotApplicable, so averaging tools
        // naturally exclude it rather than needing to filter a sentinel value.
        Map<String, Object> flatCategoryScores = new LinkedHashMap<>();
        double earnedPoints = 0;
        double availablePoints = 0;
        for (Map.Entry<String, Object> categoryEntry : orderedCategoryScores.entrySet()) {
            if (categoryEntry.getValue() instanceof Map<?, ?> m) {
                Integer numericScore = asNumericScore(m.get("score"));
                if (numericScore != null) {
                    numericScores.add(numericScore);
                    int weight = CATEGORY_WEIGHTS.getOrDefault(categoryEntry.getKey(), 1);
                    earnedPoints += numericScore * weight;
                    availablePoints += 4 * weight;
                    flatCategoryScores.put("score_" + categoryEntry.getKey(), numericScore);
                }
                // score == null or "NotApplicable": excluded from both the numeric rollup and the
                // weighted percentage, per context/QAScoring.pdf ("a ticket with no bug is scored
                // out of 68 rather than 72").
            }
        }
        int lowestCategoryScore = numericScores.stream().mapToInt(Integer::intValue).min().orElse(4);
        double overallScorePercent = availablePoints > 0
            ? Math.round((earnedPoints / availablePoints) * 1000) / 10.0
            : 100.0;

        boolean anomalyFlagged = Boolean.TRUE.equals(comm.get("anomalyFlagged"))
            || Boolean.TRUE.equals(tech.get("anomalyFlagged"))
            || Boolean.TRUE.equals(proc.get("anomalyFlagged"));

        List<String> anomalyReasons = new ArrayList<>();
        addReasons(anomalyReasons, comm.get("anomalyReasons"));
        addReasons(anomalyReasons, tech.get("anomalyReasons"));
        addReasons(anomalyReasons, proc.get("anomalyReasons"));

        // Band edges are the midpoints between the reference points in context/QAScoring.pdf
        // (straight-2s=50% Developing, straight-3s=75% Meets Standard, straight-4s=100% Excellent):
        // 62.5% sits halfway between Developing and Meets Standard, 87.5% halfway between Meets
        // Standard and Excellent. Not specified by the TLs directly - adjust here if they set
        // different cutoffs.
        String overallProvisionalRating = overallScorePercent >= 87.5 ? "Exceeds"
            : (overallScorePercent >= 62.5 ? "Meets" : "NeedsImprovement");

        // Token/model-call usage per dimension, extracted by each evaluation's own BPMN output
        // mapping from the AI agent connector's internal agentContext.metrics - summed here into
        // flat per-ticket totals so cost/token control (a stated trial evaluation goal) is a
        // reportable number instead of buried inside a nested connector-internal object.
        int totalModelCalls = asIntOrZero(vars.get("commModelCalls")) + asIntOrZero(vars.get("techModelCalls")) + asIntOrZero(vars.get("procModelCalls"));
        int totalInputTokens = asIntOrZero(vars.get("commInputTokens")) + asIntOrZero(vars.get("techInputTokens")) + asIntOrZero(vars.get("procInputTokens"));
        int totalOutputTokens = asIntOrZero(vars.get("commOutputTokens")) + asIntOrZero(vars.get("techOutputTokens")) + asIntOrZero(vars.get("procOutputTokens"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categoryScores", orderedCategoryScores);
        out.put("lowestCategoryScore", lowestCategoryScore);
        out.put("overallScorePercent", overallScorePercent);
        out.put("anomalyFlagged", anomalyFlagged);
        out.put("anomalyReasons", anomalyReasons);
        out.put("overallProvisionalRating", overallProvisionalRating);
        out.put("totalModelCalls", totalModelCalls);
        out.put("totalInputTokens", totalInputTokens);
        out.put("totalOutputTokens", totalOutputTokens);
        out.put("totalTokens", totalInputTokens + totalOutputTokens);
        out.putAll(flatCategoryScores);

        log.info("[mock] determine-overall-score lowestCategoryScore={} overallScorePercent={} anomalyFlagged={} rating={} totalTokens={} piKey={}",
            lowestCategoryScore, overallScorePercent, anomalyFlagged, overallProvisionalRating, totalInputTokens + totalOutputTokens, job.getProcessInstanceKey());
        variableCache.merge(job.getProcessInstanceKey(), out);
        return out;
    }

    private static int asIntOrZero(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static Integer asNumericScore(Object score) {
        if (score instanceof Number n) {
            return n.intValue();
        }
        if (score != null) {
            try {
                return Integer.parseInt(String.valueOf(score));
            } catch (NumberFormatException ignored) {
                // NotApplicable or unparsable.
            }
        }
        return null;
    }

    /**
     * Prefers the connector-parsed {prefix}CategoryScores/AnomalyFlagged/AnomalyReasons variables;
     * falls back to extracting the JSON object embedded in {prefix}ResponseText when the agent's
     * reply wasn't pure JSON (so the connector's own parseJson silently produced nothing).
     */
    private static Map<String, Object> resolveDimensionResult(Map<String, Object> vars, String prefix) {
        Object categoryScores = vars.get(prefix + "CategoryScores");
        Object anomalyFlagged = vars.get(prefix + "AnomalyFlagged");
        Object anomalyReasons = vars.get(prefix + "AnomalyReasons");

        if (categoryScores == null) {
            Map<String, Object> parsed = extractJsonObject(vars.get(prefix + "ResponseText"));
            if (parsed != null) {
                categoryScores = parsed.get("categoryScores");
                anomalyFlagged = parsed.get("anomalyFlagged");
                anomalyReasons = parsed.get("anomalyReasons");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("categoryScores", categoryScores);
        result.put("anomalyFlagged", anomalyFlagged);
        result.put("anomalyReasons", anomalyReasons);
        return result;
    }

    private static Map<String, Object> extractJsonObject(Object responseText) {
        if (!(responseText instanceof String text)) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JSON.readValue(text.substring(start, end + 1), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static void mergeCategoryScores(Map<String, Object> target, Object src) {
        if (src instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                target.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
    }

    private static void addReasons(List<String> target, Object src) {
        if (src instanceof List<?> l) {
            for (Object o : l) {
                if (o != null) {
                    target.add(String.valueOf(o));
                }
            }
        }
    }

    @JobWorker(type = "persist-score")
    public Map<String, Object> persistScore(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();

        Map<String, Object> qaAssessment = Map.of(
            "categoryScores", vars.getOrDefault("categoryScores", Map.of()),
            "overallProvisionalRating", vars.getOrDefault("overallProvisionalRating", ""),
            "overallScorePercent", vars.getOrDefault("overallScorePercent", 100.0),
            "lowestCategoryScore", vars.getOrDefault("lowestCategoryScore", 4),
            "anomalyFlagged", vars.getOrDefault("anomalyFlagged", false),
            "anomalyReasons", vars.getOrDefault("anomalyReasons", List.of())
        );
        Map<String, Object> out = Map.of("qaAssessment", qaAssessment);
        log.info("[mock] persist-score (AIScore) ticketId={} rating={} piKey={}",
            vars.get("zendeskTicketId"), vars.get("overallProvisionalRating"), job.getProcessInstanceKey());
        variableCache.merge(job.getProcessInstanceKey(), out);
        return out;
    }

    // ── Human-review selection ──────────────────────────────────────────────

    @JobWorker(type = "apply-sample-selection")
    public Map<String, Object> applySampleSelection(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String ticketId = String.valueOf(vars.get("zendeskTicketId"));
        boolean triggerReview = Boolean.TRUE.equals(vars.get("triggerReview"));
        String triggerReason = String.valueOf(vars.getOrDefault("triggerReason", ""));
        String overallProvisionalRating = String.valueOf(vars.getOrDefault("overallProvisionalRating", ""));
        int spotCheckPercent = ((Number) vars.getOrDefault("spotCheckSamplePercent", 5)).intValue();
        int randomBaselinePercent = ((Number) vars.getOrDefault("randomBaselineSamplePercent", 3)).intValue();

        boolean routeToHumanReview;
        String selectionChannel;
        String selectionReason;

        if (triggerReview) {
            routeToHumanReview = true;
            selectionChannel = triggerReason;
            selectionReason = "LowProvisionalScore".equals(triggerReason)
                ? "At least one rubric category scored below Meets Standard."
                : "The AI flagged this ticket as atypical (back-and-forth, frustration signals, or slow cadence).";
        } else {
            Random rnd = seeded(ticketId, "sampling");
            int spotCheckDraw = rnd.nextInt(100);
            int baselineDraw = rnd.nextInt(100);

            if ("Exceeds".equals(overallProvisionalRating) && spotCheckDraw < spotCheckPercent) {
                routeToHumanReview = true;
                selectionChannel = "HighScoreSpotCheck";
                selectionReason = "Random spot-check of a top-scoring ticket to catch AI false positives.";
            } else if (baselineDraw < randomBaselinePercent) {
                routeToHumanReview = true;
                selectionChannel = "RandomBaseline";
                selectionReason = "Random baseline draw to keep the review sample representative.";
            } else {
                routeToHumanReview = false;
                selectionChannel = "None";
                selectionReason = "Not selected for human review.";
            }
        }

        Map<String, Object> out = Map.of(
            "routeToHumanReview", routeToHumanReview,
            "selectionChannel", selectionChannel,
            "selectionReason", selectionReason
        );
        log.info("[mock] apply-sample-selection ticketId={} route={} channel={} piKey={}",
            ticketId, routeToHumanReview, selectionChannel, job.getProcessInstanceKey());
        variableCache.merge(job.getProcessInstanceKey(), out);
        return out;
    }

    // ── Final record ─────────────────────────────────────────────────────────

    @JobWorker(type = "record-qa-result")
    public Map<String, Object> recordQaResult(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String finalRating = vars.get("finalRating") != null ? String.valueOf(vars.get("finalRating")) : null;
        String overallProvisionalRating = String.valueOf(vars.getOrDefault("overallProvisionalRating", ""));

        String effectiveRating = finalRating != null ? finalRating : overallProvisionalRating;
        String finalRatingSource = finalRating != null ? "human" : "ai-unreviewed";
        boolean overridden = finalRating != null && !finalRating.equals(overallProvisionalRating);

        Map<String, Object> out = Map.of(
            "effectiveRating", effectiveRating,
            "finalRatingSource", finalRatingSource,
            "overridden", overridden,
            "qaRecordId", "QA-" + (job.getProcessInstanceKey() % 1_000_000),
            "recordedAt", Instant.now().toString()
        );
        log.info("[mock] record-qa-result rating={} source={} overridden={} piKey={}",
            effectiveRating, finalRatingSource, overridden, job.getProcessInstanceKey());
        variableCache.merge(job.getProcessInstanceKey(), out);
        return out;
    }
}
