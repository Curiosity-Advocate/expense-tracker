package com.finance.domain;

import java.time.Instant;

public record TokenPair(String accessToken, Instant expiresAt, String tokenType) {}
