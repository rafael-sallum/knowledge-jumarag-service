package com.jumatech.jumarag.shared.web;

import org.springframework.http.HttpStatus;

public final class DomainException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public DomainException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
