package com.finance.dto;

import java.time.Instant;

public record LoginResponse(String accessToken, Instant expiresAt, String tokenType) {}
