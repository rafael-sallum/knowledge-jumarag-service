package com.jumatech.jumarag.context;

public interface ContextStrategy {
    ContextStrategyType type();

    PreparedContext prepare(ContextPreparationRequest request);
}
