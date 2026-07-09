package com.camunda.loanoriginationmortgage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LoanOriginationMortgageApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoanOriginationMortgageApplication.class, args);
    }

}
