package com.finance.service;

import com.finance.command.LoginCommand;
import com.finance.command.RegisterCommand;
import com.finance.domain.RegisteredUser;
import com.finance.domain.TokenPair;

public interface AuthService {
    RegisteredUser register(RegisterCommand command);
    TokenPair login(LoginCommand command);
    void logout(String rawToken);
}
