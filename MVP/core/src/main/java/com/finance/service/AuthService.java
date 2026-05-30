package com.finance.service;

import com.finance.command.LoginCommand;
import com.finance.command.RefreshTokenCommand;
import com.finance.command.RegisterCommand;
import com.finance.domain.RegisteredUser;
import com.finance.domain.TokenPair;

public interface AuthService {
    RegisteredUser register(RegisterCommand command);

    // Issues a fresh access token + a brand-new refresh-token chain.
    TokenPair login(LoginCommand command);

    // Rotates the presented refresh token: marks it ROTATED, issues a new
    // access + refresh pair with the same session_started_at (no extension).
    // Throws InvalidRefreshTokenException if the token is unknown or expired,
    // or RefreshTokenReuseException if it has already been rotated (chain is
    // revoked as a side effect — caller treats this as forced re-login).
    TokenPair refresh(RefreshTokenCommand command);

    // Revokes the presented refresh token (LOGOUT reason). The access token
    // is NOT touched — it expires naturally within the access-token window.
    void logout(String refreshToken);
}
