package com.finance.exception;

// Thrown when username does not exist or password does not match.
// The message is intentionally identical in both cases — never reveal which one failed.
// This prevents user enumeration: an attacker cannot distinguish "wrong username"
// from "wrong password" based on the error response.
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid username or password");
    }
}