package com.jumatech.jumarag.provider;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
public final class ProviderCallExecutor {
    private static final Logger log = LoggerFactory.getLogger(ProviderCallExecutor.class);
    private final ProviderProperties.Resilience properties;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final Map<String, Circuit> circuits = new ConcurrentHashMap<>();

    @Autowired
    public ProviderCallExecutor(ProviderProperties properties, MeterRegistry metrics) {
        this(properties.resilience(), metrics, Clock.systemUTC());
    }

    ProviderCallExecutor(ProviderProperties.Resilience properties, MeterRegistry metrics, Clock clock) {
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    public <T> T execute(String provider, String model, Operation operation, Supplier<T> call) {
        String key = provider + ':' + model + ':' + operation;
        Circuit circuit = circuits.computeIfAbsent(key, ignored -> new Circuit());
        Instant now = clock.instant();
        if (circuit.isOpen(now)) {
            metrics.counter("jumarag.provider.circuit.open", "provider", provider, "model", model,
                    "operation", operation.name()).increment();
            throw new ProviderException(ProviderException.Reason.CIRCUIT_OPEN);
        }
        Timer.Sample sample = Timer.start(metrics);
        try {
            T result = call.get();
            circuit.success();
            metrics.counter("jumarag.provider.requests", "provider", provider, "model", model,
                    "operation", operation.name(), "outcome", "success").increment();
            return result;
        } catch (RuntimeException failure) {
            ProviderException mapped = map(failure);
            circuit.failure(now, properties.failureThreshold(), properties.openDuration());
            metrics.counter("jumarag.provider.requests", "provider", provider, "model", model,
                    "operation", operation.name(), "outcome", mapped.reason().name()).increment();
            if (mapped.reason() == ProviderException.Reason.TIMEOUT) {
                metrics.counter("jumarag.provider.timeouts", "provider", provider, "model", model,
                        "operation", operation.name()).increment();
            }
            log.warn("AI provider call failed provider={} model={} operation={} reason={}",
                    provider, model, operation, mapped.reason());
            throw mapped;
        } finally {
            sample.stop(metrics.timer("jumarag.provider.duration", "provider", provider, "model", model,
                    "operation", operation.name()));
        }
    }

    private static ProviderException map(RuntimeException failure) {
        if (failure instanceof ProviderException providerException) {
            return providerException;
        }
        RestClientResponseException response = cause(failure, RestClientResponseException.class);
        if (response != null) {
            HttpStatusCode status = response.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                return new ProviderException(ProviderException.Reason.AUTHENTICATION, failure);
            }
            if (status.value() == 429) {
                return new ProviderException(ProviderException.Reason.RATE_LIMITED, failure);
            }
            if (status.is5xxServerError()) {
                return new ProviderException(ProviderException.Reason.UNAVAILABLE, failure);
            }
        }
        ResourceAccessException access = cause(failure, ResourceAccessException.class);
        if (access != null && hasTimeoutCause(failure)) {
            return new ProviderException(ProviderException.Reason.TIMEOUT, failure);
        }
        if (access != null) {
            return new ProviderException(ProviderException.Reason.UNAVAILABLE, failure);
        }
        return new ProviderException(ProviderException.Reason.FAILURE, failure);
    }

    private static boolean hasTimeoutCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static <T extends Throwable> T cause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    public enum Operation {
        EMBEDDING,
        CHAT
    }

    private static final class Circuit {
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicReference<Instant> openUntil = new AtomicReference<>();

        boolean isOpen(Instant now) {
            Instant until = openUntil.get();
            if (until == null) {
                return false;
            }
            if (now.isBefore(until)) {
                return true;
            }
            openUntil.compareAndSet(until, null);
            failures.set(0);
            return false;
        }

        void success() {
            failures.set(0);
            openUntil.set(null);
        }

        void failure(Instant now, int threshold, java.time.Duration duration) {
            if (failures.incrementAndGet() >= threshold) {
                openUntil.set(now.plus(duration));
            }
        }
    }
}
