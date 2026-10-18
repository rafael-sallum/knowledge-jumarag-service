package com.jumatech.jumarag.operations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.runtime", havingValue = "worker")
public final class WorkerBoundary implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(WorkerBoundary.class);

    @Override
    public void run(ApplicationArguments args) {
        log.info("Phase-0 worker boundary ready; ingestion jobs are intentionally not implemented");
    }
}
