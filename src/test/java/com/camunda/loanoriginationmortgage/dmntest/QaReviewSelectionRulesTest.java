package com.camunda.loanoriginationmortgage.dmntest;

import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.process.test.api.assertions.DecisionSelectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

/**
 * Verifies the QaReviewSelectionRules decision table (dmn/qa-review-selection-rules.dmn) directly
 * against an embedded, Testcontainers-managed Zeebe broker - independent of the live SaaS
 * cluster, whose health has been unreliable this trial. Exercises the FIRST hit policy's three
 * rules, the lowestCategoryScore <= 2 boundary from both sides, and the case where both
 * conditions are true at once (confirms rule 1 - LowProvisionalScore - wins over rule 2 -
 * AnomalyFlagged - per FIRST's top-to-bottom priority, not just that a rule matches).
 *
 * Deploys the DMN manually via CamundaClient rather than the @TestDeployment annotation, which
 * requires Camunda Process Test 8.9+ - this project is pinned to 8.8.21. The client proxy is
 * only live inside an actual @Test method, so deployment happens per-test (@BeforeEach) rather
 * than once for the class - each deploy just creates a new (harmless) DMN version.
 */
@SpringBootTest(classes = DmnTestApplication.class)
@CamundaSpringProcessTest
class QaReviewSelectionRulesTest {

    @Autowired
    private CamundaClient camundaClient;

    @BeforeEach
    void deployDecision() {
        camundaClient.newDeployResourceCommand()
            .addResourceFromClasspath("dmn/qa-review-selection-rules.dmn")
            .send().join();
    }

    private void evaluate(int lowestCategoryScore, boolean anomalyFlagged) {
        camundaClient.newEvaluateDecisionCommand()
            .decisionId("QaReviewSelectionRules")
            .variables(Map.of(
                "lowestCategoryScore", lowestCategoryScore,
                "anomalyFlagged", anomalyFlagged))
            .send().join();
    }

    @Test
    void belowThresholdScoreTriggersLowProvisionalScore() {
        evaluate(1, false);

        CamundaAssert.assertThatDecision(DecisionSelectors.byId("QaReviewSelectionRules"))
            .isEvaluated()
            .hasOutput(Map.of("triggerReview", true, "triggerReason", "LowProvisionalScore"))
            .hasMatchedRules(1);
    }

    @Test
    void scoreAtExactBoundaryStillTriggers() {
        // lowestCategoryScore <= 2 is an inclusive boundary - 2 itself must trigger rule 1.
        evaluate(2, false);

        CamundaAssert.assertThatDecision(DecisionSelectors.byId("QaReviewSelectionRules"))
            .isEvaluated()
            .hasOutput(Map.of("triggerReview", true, "triggerReason", "LowProvisionalScore"))
            .hasMatchedRules(1);
    }

    @Test
    void justAboveBoundaryWithAnomalyTriggersAnomalyFlagged() {
        // lowestCategoryScore = 3 must NOT match rule 1 (confirms the boundary from the other
        // side); the anomaly flag alone should fall through to rule 2.
        evaluate(3, true);

        CamundaAssert.assertThatDecision(DecisionSelectors.byId("QaReviewSelectionRules"))
            .isEvaluated()
            .hasOutput(Map.of("triggerReview", true, "triggerReason", "AnomalyFlagged"))
            .hasMatchedRules(2);
    }

    @Test
    void healthyTicketDoesNotTriggerReview() {
        evaluate(4, false);

        CamundaAssert.assertThatDecision(DecisionSelectors.byId("QaReviewSelectionRules"))
            .isEvaluated()
            .hasOutput(Map.of("triggerReview", false, "triggerReason", ""))
            .hasMatchedRules(3);
    }

    @Test
    void bothConditionsTrueResolvesToFirstMatchingRule() {
        // Both rule 1 (score <= 2) and rule 2 (anomalyFlagged) match here. FIRST hit policy must
        // resolve to rule 1's output (LowProvisionalScore), not rule 2's (AnomalyFlagged) -
        // proving rule priority/ordering, not just that some rule fires.
        evaluate(2, true);

        CamundaAssert.assertThatDecision(DecisionSelectors.byId("QaReviewSelectionRules"))
            .isEvaluated()
            .hasOutput(Map.of("triggerReview", true, "triggerReason", "LowProvisionalScore"))
            .hasMatchedRules(1);
    }
}
