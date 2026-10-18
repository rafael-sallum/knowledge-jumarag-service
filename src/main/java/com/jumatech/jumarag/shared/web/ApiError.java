package com.jumatech.jumarag.shared.web;

public record ApiError(String code, String message, String requestId) {}
