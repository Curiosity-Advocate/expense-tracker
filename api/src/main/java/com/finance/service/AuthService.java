package com.finance.service;

import com.finance.command.RegisterCommand;
import com.finance.domain.RegisteredUser;

// The contract. Lives in core so both api and worker can depend on it.
// Only register for now — login and logout are added in steps 5 and 6.
// We do not pre-emptively define methods we have not built yet.
public interface AuthService {
    RegisteredUser register(RegisterCommand command);
    TokenPair login(LoginCommand command);
}