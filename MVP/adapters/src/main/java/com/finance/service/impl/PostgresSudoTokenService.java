package com.finance.service.impl;

import com.finance.command.CreateSudoTokenCommand;
import com.finance.domain.SudoToken;
import com.finance.domain.SudoTokenVerification;
import com.finance.entity.AccessGrantEntity;
import com.finance.entity.SudoTokenEntity;
import com.finance.entity.UserEntity;
import com.finance.exception.GrantNotUsableException;
import com.finance.exception.InvalidCredentialsException;
import com.finance.exception.InvalidSudoTokenException;
import com.finance.repository.AccessGrantRepository;
import com.finance.repository.SudoTokenRepository;
import com.finance.repository.UserRepository;
import com.finance.security.SecureTokenGenerator;
import com.finance.security.SecureTokenGenerator.GeneratedToken;
import com.finance.service.SudoTokenService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

// Runs on the app pool. The grantee mints a sudo token by re-entering their
// password (step-up auth); D3's gateway filter calls verify() to check the
// token before substituting user identities on a delegation request.
@Service
public class PostgresSudoTokenService implements SudoTokenService {

    // 15 minutes matches the access-token window. Long enough for the
    // grantee to perform a corrective action; short enough that a leaked
    // sudo token's window is bounded. Could be made configurable via a
    // properties class later if needed.
    private static final int SUDO_TOKEN_EXPIRY_MINUTES = 15;

    private final AccessGrantRepository  grantRepository;
    private final SudoTokenRepository    sudoTokenRepository;
    private final UserRepository         userRepository;
    private final SecureTokenGenerator   tokenGenerator;
    private final BCryptPasswordEncoder  passwordEncoder;
    private final Clock                  clock;

    public PostgresSudoTokenService(AccessGrantRepository grantRepository,
                                    SudoTokenRepository sudoTokenRepository,
                                    UserRepository userRepository,
                                    SecureTokenGenerator tokenGenerator,
                                    BCryptPasswordEncoder passwordEncoder,
                                    Clock clock) {
        this.grantRepository     = grantRepository;
        this.sudoTokenRepository = sudoTokenRepository;
        this.userRepository      = userRepository;
        this.tokenGenerator      = tokenGenerator;
        this.passwordEncoder     = passwordEncoder;
        this.clock               = clock;
    }

    @Override
    @Transactional
    public SudoToken create(CreateSudoTokenCommand command) {
        // findByIdAndPartyTo (from D1) returns the grant only if the requesting
        // user is grantor or grantee. For sudo-token minting we additionally
        // require the user to be the grantee specifically — enforced by
        // checking grant.granteeId after the lookup. Unified to
        // GrantNotUsableException to prevent enumeration.
        AccessGrantEntity grant = grantRepository
                .findByIdAndPartyTo(command.grantId(), command.granteeId())
                .orElseThrow(GrantNotUsableException::new);

        Instant now = Instant.now(clock);
        if (!grant.getGranteeId().equals(command.granteeId())
                || grant.getRevokedAt() != null
                || grant.getExpiresAt().isBefore(now)) {
            throw new GrantNotUsableException();
        }

        UserEntity user = userRepository.findById(command.granteeId())
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        GeneratedToken token = tokenGenerator.generate();
        Instant expiresAt = now.plus(SUDO_TOKEN_EXPIRY_MINUTES, ChronoUnit.MINUTES);

        SudoTokenEntity entity = new SudoTokenEntity();
        entity.setTokenHash(token.hash());
        entity.setGrantId(grant.getId());
        entity.setGranteeId(grant.getGranteeId());
        entity.setExpiresAt(expiresAt);
        sudoTokenRepository.save(entity);

        return new SudoToken(token.rawToken(), expiresAt);
    }

    @Override
    @Transactional(readOnly = true)
    public SudoTokenVerification verify(String rawToken, UUID granteeId) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidSudoTokenException();
        }
        Instant now = Instant.now(clock);
        String hash = tokenGenerator.hash(rawToken);

        SudoTokenEntity tokenRow = sudoTokenRepository
                .findByTokenHashAndGranteeIdAndExpiresAtAfter(hash, granteeId, now)
                .orElseThrow(InvalidSudoTokenException::new);

        // Re-check the underlying grant — it may have been revoked or
        // expired after the sudo token was issued. The grant FK ensures
        // the row exists; the state fields may have moved on.
        AccessGrantEntity grant = grantRepository.findById(tokenRow.getGrantId())
                .orElseThrow(InvalidSudoTokenException::new);
        if (grant.getRevokedAt() != null || grant.getExpiresAt().isBefore(now)) {
            throw new InvalidSudoTokenException();
        }

        return new SudoTokenVerification(grant.getId(), grant.getGrantorId(), granteeId);
    }
}
