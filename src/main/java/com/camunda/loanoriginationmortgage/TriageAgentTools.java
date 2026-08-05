package com.camunda.loanoriginationmortgage;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Tools for the AI risk triage agent (AdHocSubProcess_TriageAgent in ai-governance.bpmn).
 * Each method backs one tool the agent can call; results are deterministic lookups against
 * small static datasets rather than real policy/precedent systems — swap for real integrations
 * as needed. The point being demonstrated is that every call here shows up as its own traced
 * BPMN element in the process instance's history, not just a line in a chat transcript.
 */
@Component
@Profile("!test")
public class TriageAgentTools {

    private static final Logger log = LoggerFactory.getLogger(TriageAgentTools.class);

    private static final Map<String, String> POLICY_LIBRARY = new LinkedHashMap<>();
    static {
        POLICY_LIBRARY.put("data privacy", "Policy DP-1: Personal data used in an AI system must have a documented lawful basis and a defined retention period.");
        POLICY_LIBRARY.put("biometric", "Policy BIO-2: Biometric identifiers (face, fingerprint, voice) require Legal and Privacy sign-off before any automated matching or verification use.");
        POLICY_LIBRARY.put("financial", "Policy FIN-3: Systems that influence credit, lending, or transaction decisions require Compliance review against fair-lending requirements.");
        POLICY_LIBRARY.put("employment", "Policy HR-4: AI used in hiring, performance review, or termination decisions requires adverse-impact testing before launch.");
        POLICY_LIBRARY.put("automated decision", "Policy ADM-5: Any system that can take action without human review requires an autonomy-level assessment and a documented rollback plan.");
        POLICY_LIBRARY.put("security", "Policy SEC-6: Systems with production data access require a threat model and secrets-handling review before launch.");
        POLICY_LIBRARY.put("health", "Policy HLT-7: Systems touching health information require HIPAA-equivalent handling review regardless of stated data sensitivity.");
    }

    private static final List<Map<String, String>> PRECEDENTS = List.of(
        Map.of("name", "Customer support chatbot",        "category", "Minimal",  "outcome", "Approved",                                  "date", "2025-09-12"),
        Map.of("name", "Internal FAQ search assistant",    "category", "Limited",  "outcome", "Approved",                                  "date", "2025-10-03"),
        Map.of("name", "Resume screening assistant",       "category", "High",     "outcome", "Approved with monitoring",                  "date", "2025-11-18"),
        Map.of("name", "Fraud detection scoring engine",   "category", "High",     "outcome", "Approved with monitoring",                  "date", "2025-12-02"),
        Map.of("name", "Automated loan approval engine",   "category", "Critical", "outcome", "Rejected — required human sign-off added",  "date", "2026-01-15"),
        Map.of("name", "Medical diagnosis support tool",   "category", "Critical", "outcome", "Approved with quarterly recertification",   "date", "2026-02-20"),
        Map.of("name", "Predictive maintenance alerting",  "category", "High",     "outcome", "Approved",                                  "date", "2026-03-05"),
        Map.of("name", "Marketing copy generator",         "category", "Minimal",  "outcome", "Approved",                                  "date", "2026-04-11")
    );

    @JobWorker(type = "triage_search_policy_docs")
    public Map<String, Object> searchPolicyDocs(ActivatedJob job) {
        String query = String.valueOf(job.getVariablesAsMap().getOrDefault("query", "")).toLowerCase();

        List<String> matches = new ArrayList<>();
        for (var entry : POLICY_LIBRARY.entrySet()) {
            if (query.contains(entry.getKey()) || entry.getKey().contains(query)) {
                matches.add(entry.getValue());
            }
        }
        if (matches.isEmpty()) {
            matches.add("No specific policy matched \"" + query + "\" — default governance policy GOV-0 applies: "
                + "all new AI use cases require the standard review slate before launch.");
        }

        log.info("[triage-agent] search_policy_docs query=\"{}\" matches={}", query, matches.size());
        return Map.of("toolCallResult", String.join("\n", matches));
    }

    @JobWorker(type = "triage_scan_sensitive_data")
    public Map<String, Object> scanSensitiveData(ActivatedJob job) {
        String text = String.valueOf(job.getVariablesAsMap().getOrDefault("text", "")).toLowerCase();

        Map<String, List<String>> categoryKeywords = new LinkedHashMap<>();
        categoryKeywords.put("PII",        List.of("ssn", "social security", "name", "address", "email"));
        categoryKeywords.put("Health",     List.of("health", "medical", "diagnosis", "patient", "clinical"));
        categoryKeywords.put("Biometric",  List.of("face", "fingerprint", "voice", "biometric", "iris"));
        categoryKeywords.put("Financial",  List.of("credit", "loan", "bank", "transaction", "payment"));
        categoryKeywords.put("Location",   List.of("location", "gps", "geolocation", "tracking"));
        categoryKeywords.put("Minors",     List.of("minor", "child", "student", "underage"));
        categoryKeywords.put("Employment", List.of("hiring", "resume", "performance review", "termination", "employee"));

        List<String> detected = new ArrayList<>();
        for (var entry : categoryKeywords.entrySet()) {
            for (String kw : entry.getValue()) {
                if (text.contains(kw)) { detected.add(entry.getKey()); break; }
            }
        }

        String result = detected.isEmpty()
            ? "No specific sensitive-data categories detected from the text alone — this is a heuristic scan, not a guarantee; confirm with the use case owner."
            : "Detected possible categories: " + String.join(", ", detected)
                + ". This is a heuristic keyword scan, not a guarantee — reviewers should confirm with the use case owner.";

        log.info("[triage-agent] scan_sensitive_data detected={}", detected);
        return Map.of("toolCallResult", result);
    }

    @JobWorker(type = "triage_find_similar_use_cases")
    public Map<String, Object> findSimilarUseCases(ActivatedJob job) {
        String keywords = String.valueOf(job.getVariablesAsMap().getOrDefault("keywords", "")).toLowerCase();
        Set<String> queryWords = new HashSet<>(Arrays.asList(keywords.split("\\W+")));
        queryWords.removeIf(String::isBlank);

        List<Map<String, String>> scored = new ArrayList<>();
        for (var precedent : PRECEDENTS) {
            String name = precedent.get("name").toLowerCase();
            if (queryWords.stream().anyMatch(name::contains)) scored.add(precedent);
        }
        if (scored.isEmpty()) {
            scored = PRECEDENTS.subList(0, Math.min(2, PRECEDENTS.size()));
        } else if (scored.size() > 3) {
            scored = scored.subList(0, 3);
        }

        StringBuilder sb = new StringBuilder();
        for (var p : scored) {
            sb.append("- ").append(p.get("name")).append(" — ").append(p.get("category"))
              .append(" risk, ").append(p.get("outcome")).append(" (").append(p.get("date")).append(")\n");
        }

        log.info("[triage-agent] find_similar_use_cases keywords=\"{}\" matches={}", keywords, scored.size());
        return Map.of("toolCallResult", sb.toString().trim());
    }
}