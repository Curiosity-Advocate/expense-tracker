package com.finance.exception;

// Typed exception — thrown by the service when username or email is taken.
// The message is intentionally generic. We never say which field conflicted.
public class UserAlreadyExistsException extends RuntimeException {
    public UserAlreadyExistsException() {
        super("Username or email already in use");
    }
}