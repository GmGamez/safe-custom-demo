package com.camunda.loanoriginationmortgage;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/index.html");
        registry.addViewController("/start").setViewName("forward:/start.html");
        registry.addViewController("/admin").setViewName("forward:/admin.html");
        registry.addViewController("/tasks").setViewName("forward:/tasks.html");
        registry.addViewController("/audit").setViewName("forward:/audit.html");
    }
}
