package com.finance.exception;

public class InvalidCredentialsException extends RuntimeException {
    // Generic message — never say "wrong password" vs "user not found"
    // because that reveals whether a username exists (user enumeration).
    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
