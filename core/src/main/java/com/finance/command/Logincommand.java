package com.finance.command;

// Password arrives as plaintext — the service verifies it against the stored hash.
public record LoginCommand(
        String username,
        String password
) {}