package com.camunda.loanoriginationmortgage.dmntest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot app for decision-only Camunda Process Test runs. Deliberately isolated in
 * its own sub-package (component scan only covers dmntest and below) so it never picks up the
 * production app's beans (ResourceDeployer, SupportQaWorkers, AdminController, etc.), which
 * require the live SaaS cluster's connection properties this test must stay independent of.
 */
@SpringBootApplication
public class DmnTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(DmnTestApplication.class, args);
    }
}
