package com.jumatech.jumarag.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

class ProviderCallExecutorTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void mapsProviderFailuresWithoutLeakingResponseContent() {
        ProviderCallExecutor executor = executor(3);
        String sensitive = "sensitive-provider-response";

        assertThatThrownBy(() -> executor.execute("OPENAI", "model", ProviderCallExecutor.Operation.CHAT,
                        () -> {
                            throw new HttpClientErrorException(
                                    HttpStatus.TOO_MANY_REQUESTS, sensitive, sensitive.getBytes(), null);
                        }))
                .isInstanceOfSatisfying(ProviderException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(ProviderException.Reason.RATE_LIMITED);
                    assertThat(exception.getMessage()).doesNotContain(sensitive);
                });

        assertThatThrownBy(() -> executor.execute("OPENAI", "model", ProviderCallExecutor.Operation.EMBEDDING,
                        () -> {
                            throw new ResourceAccessException("request failed", new SocketTimeoutException());
                        }))
                .isInstanceOfSatisfying(ProviderException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(ProviderException.Reason.TIMEOUT));

        assertThatThrownBy(() -> executor.execute("OPENAI", "model", ProviderCallExecutor.Operation.CHAT,
                        () -> {
                            throw new RuntimeException("provider wrapper",
                                    new HttpClientErrorException(HttpStatus.UNAUTHORIZED, sensitive));
                        }))
                .isInstanceOfSatisfying(ProviderException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(ProviderException.Reason.AUTHENTICATION);
                    assertThat(exception.getMessage()).doesNotContain(sensitive);
                });
    }

    @Test
    void opensCircuitAfterConfiguredFailuresAndDoesNotInvokeProviderAgain() {
        ProviderCallExecutor executor = executor(2);
        AtomicInteger calls = new AtomicInteger();
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> executor.execute("OPENAI", "model", ProviderCallExecutor.Operation.CHAT,
                            () -> {
                                calls.incrementAndGet();
                                throw new ResourceAccessException("unavailable");
                            }))
                    .isInstanceOf(ProviderException.class);
        }

        assertThatThrownBy(() -> executor.execute("OPENAI", "model", ProviderCallExecutor.Operation.CHAT,
                        () -> {
                            calls.incrementAndGet();
                            return "unexpected";
                        }))
                .isInstanceOfSatisfying(ProviderException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(ProviderException.Reason.CIRCUIT_OPEN));
        assertThat(calls).hasValue(2);
    }

    private ProviderCallExecutor executor(int threshold) {
        return new ProviderCallExecutor(
                new ProviderProperties.Resilience(threshold, Duration.ofSeconds(30)),
                new SimpleMeterRegistry(),
                clock);
    }
}
