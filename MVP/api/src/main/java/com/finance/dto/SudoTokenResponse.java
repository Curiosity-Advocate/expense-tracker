package com.finance.dto;

import java.time.Instant;

// Raw token is returned exactly once. Client must store it for the
// duration of the delegation session (15 min) and send it in the
// X-Sudo-Token header on D3's ?asUserId= requests.
public record SudoTokenResponse(String sudoToken, Instant expiresAt) {}
