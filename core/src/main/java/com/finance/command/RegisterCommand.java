package com.finance.command;

// A record is an immutable data carrier — no setters, no boilerplate.
// This is what crosses the boundary from the HTTP layer into the service layer.
// The service never sees a RegisterRequest (that's HTTP vocabulary).
// It only sees this command (domain vocabulary).
public record RegisterCommand(
        String username,
        String email,
        String password  // plaintext — service is responsible for hashing
) {}