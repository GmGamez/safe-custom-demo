package com.camunda.loanoriginationmortgage;

import org.springframework.boot.SpringApplication;

public class TestLoanOriginationMortgageApplication {

    public static void main(String[] args) {
        SpringApplication.from(LoanOriginationMortgageApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
