package com.camunda.loanoriginationmortgage;

import io.camunda.client.CamundaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ResourceDeployer {

    private static final Logger log = LoggerFactory.getLogger(ResourceDeployer.class);

    private static final String[] PATTERNS = {
            "classpath*:/bpmn/**/*.bpmn",
            "classpath*:/dmn/**/*.dmn",
            "classpath*:/forms/**/*.form"
    };

    private final CamundaClient client;

    public ResourceDeployer(CamundaClient client) {
        this.client = client;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void deploy() {
        var resolver = new PathMatchingResourcePatternResolver();
        List<Resource> resources = new ArrayList<>();

        for (String pattern : PATTERNS) {
            try {
                for (var r : resolver.getResources(pattern)) {
                    if (r.isReadable()) resources.add(r);
                }
            } catch (IOException e) {
                log.warn("[deploy] could not scan {}: {}", pattern, e.getMessage());
            }
        }

        if (resources.isEmpty()) {
            log.warn("[deploy] no deployable resources found");
            return;
        }

        try {
            var cmd = client.newDeployResourceCommand()
                    .addResourceBytes(resources.get(0).getInputStream().readAllBytes(),
                                      resources.get(0).getFilename());
            for (int i = 1; i < resources.size(); i++) {
                var r = resources.get(i);
                cmd = cmd.addResourceBytes(r.getInputStream().readAllBytes(), r.getFilename());
            }
            var result = cmd.send().get(30, TimeUnit.SECONDS);
            log.info("[deploy] {} resource(s) deployed — key {}", resources.size(), result.getKey());
        } catch (Exception e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            log.warn("[deploy] startup deployment failed — resources must be deployed via Camunda Web Modeler or Modeler desktop: {}", msg);
        }
    }
}
