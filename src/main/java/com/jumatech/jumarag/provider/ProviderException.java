package com.jumatech.jumarag.provider;

public final class ProviderException extends RuntimeException {
    private final Reason reason;

    public ProviderException(Reason reason) {
        this(reason, null);
    }

    public ProviderException(Reason reason, Throwable cause) {
        super(safeMessage(reason), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    private static String safeMessage(Reason reason) {
        return switch (reason) {
            case AUTHENTICATION -> "AI provider authentication failed.";
            case RATE_LIMITED -> "AI provider rate limit was reached.";
            case TIMEOUT -> "AI provider request timed out.";
            case UNAVAILABLE -> "AI provider is temporarily unavailable.";
            case CIRCUIT_OPEN -> "AI provider circuit is temporarily open.";
            case MALFORMED_RESPONSE -> "AI provider returned an invalid response.";
            case INCOMPATIBLE_VECTOR -> "AI provider returned an incompatible embedding vector.";
            case FAILURE -> "AI provider request failed.";
        };
    }

    public enum Reason {
        AUTHENTICATION,
        RATE_LIMITED,
        TIMEOUT,
        UNAVAILABLE,
        CIRCUIT_OPEN,
        MALFORMED_RESPONSE,
        INCOMPATIBLE_VECTOR,
        FAILURE
    }
}
