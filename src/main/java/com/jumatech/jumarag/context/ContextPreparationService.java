package com.jumatech.jumarag.context;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public final class ContextPreparationService {
    private final Map<ContextStrategyType, ContextStrategy> strategies;
    private final MeterRegistry metrics;

    public ContextPreparationService(List<ContextStrategy> strategies, MeterRegistry metrics) {
        Map<ContextStrategyType, ContextStrategy> indexed = new EnumMap<>(ContextStrategyType.class);
        strategies.forEach(strategy -> indexed.put(strategy.type(), strategy));
        this.strategies = Map.copyOf(indexed);
        this.metrics = metrics;
    }

    public PreparedContext prepare(ContextPreparationRequest request) {
        ContextStrategyType configured = request.knowledgeBase().contextStrategy();
        ContextStrategy strategy = strategies.get(configured);
        if (strategy == null) {
            throw new IllegalStateException("No context strategy is registered for " + configured);
        }
        long started = System.nanoTime();
        try {
            PreparedContext prepared = strategy.prepare(request);
            metrics.counter("jumarag.context.prepared", "configured", configured.name(),
                    "used", prepared.usedStrategy().name()).increment();
            if (configured == ContextStrategyType.AUTO) {
                metrics.counter("jumarag.context.auto.selection", "used", prepared.usedStrategy().name()).increment();
            }
            metrics.summary("jumarag.context.chunks", "strategy", prepared.usedStrategy().name())
                    .record(prepared.evidence().size());
            metrics.summary("jumarag.context.estimated.tokens", "strategy", prepared.usedStrategy().name())
                    .record(prepared.estimatedTokens());
            return prepared;
        } catch (RuntimeException exception) {
            metrics.counter("jumarag.context.failures", "configured", configured.name()).increment();
            throw exception;
        } finally {
            metrics.timer("jumarag.context.preparation.duration", "configured", configured.name())
                    .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
}
