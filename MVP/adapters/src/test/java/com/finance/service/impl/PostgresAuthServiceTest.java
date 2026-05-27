package com.finance.service.impl;

import com.finance.command.LoginCommand;
import com.finance.command.RegisterCommand;
import com.finance.domain.TokenPair;
import com.finance.entity.UserEntity;
import com.finance.entity.UserLoginFailureEntity;
import com.finance.exception.AccountLockedException;
import com.finance.exception.InvalidCredentialsException;
import com.finance.exception.UserAlreadyExistsException;
import com.finance.repository.RevokedTokenRepository;
import com.finance.repository.UserLoginFailureRepository;
import com.finance.repository.UserRepository;
import com.finance.security.JwtService;
import com.finance.security.RoleElevationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostgresAuthServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-11T10:00:00Z"), ZoneOffset.UTC);

    @Mock UserRepository userRepository;
    @Mock UserLoginFailureRepository loginFailureRepository;
    @Mock RevokedTokenRepository revokedTokenRepository;
    @Mock BCryptPasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock RoleElevationService roleElevationService;

    // Clock is not a mock so we construct the service manually
    private PostgresAuthService service;

    @BeforeEach
    void setUp() {
        service = new PostgresAuthService(
                userRepository, loginFailureRepository, revokedTokenRepository,
                passwordEncoder, jwtService, FIXED_CLOCK, roleElevationService);
    }

    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setUsername("john");
        user.setEmail("john@example.com");
        user.setPasswordHash("hashed_password");
        return user;
    }

    @Nested
    class Login {

        @Test
        void happyPath_returnsTokenPair() {
            UserEntity user = activeUser();
            LoginCommand command = new LoginCommand("john", "correct_password");
            Instant expiry = Instant.parse("2026-01-18T10:00:00Z");

            when(userRepository.findByUsername("john")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("correct_password", "hashed_password")).thenReturn(true);
            when(jwtService.generateToken(user.getId(), "john")).thenReturn("jwt_token");
            when(jwtService.getExpiry("jwt_token")).thenReturn(expiry);

            TokenPair result = service.login(command);

            assertThat(result.accessToken()).isEqualTo("jwt_token");
            assertThat(result.tokenType()).isEqualTo("Bearer");
        }

        @Test
        void wrongPassword_throwsInvalidCredentials_andRecordsFailureRow() {
            UserEntity user = activeUser();
            LoginCommand command = new LoginCommand("john", "wrong_password");

            when(userRepository.findByUsername("john")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong_password", "hashed_password")).thenReturn(false);
            // Only 1 failure in the window — no lockout yet
            when(loginFailureRepository.countByUserIdAndAttemptedAtAfter(any(), any()))
                    .thenReturn(1L);

            assertThatThrownBy(() -> service.login(command))
                    .isInstanceOf(InvalidCredentialsException.class);

            // The new row was inserted
            verify(loginFailureRepository).save(any(UserLoginFailureEntity.class));
            // Below threshold — no save on users (no lockout to set)
            verify(userRepository, never()).save(user);
            assertThat(user.getLockedUntil()).isNull();
        }

        // v1.1 #2 — 4 failures in the last 10 minutes does NOT lock the account.
        // (The repository count includes the just-inserted row.)
        @Test
        void fourFailuresInWindow_doesNotLock() {
            UserEntity user = activeUser();
            LoginCommand command = new LoginCommand("john", "wrong_password");

            when(userRepository.findByUsername("john")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(false);
            when(loginFailureRepository.countByUserIdAndAttemptedAtAfter(any(), any()))
                    .thenReturn(4L);

            assertThatThrownBy(() -> service.login(command))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(loginFailureRepository).save(any(UserLoginFailureEntity.class));
            verify(userRepository, never()).save(user);
            assertThat(user.getLockedUntil()).isNull();
        }

        // v1.1 #2 — 5th failure within the 10-minute window locks the account for 15 minutes.
        @Test
        void fifthFailureInWindow_locksFor15Minutes() {
            UserEntity user = activeUser();
            LoginCommand command = new LoginCommand("john", "wrong_password");

            when(userRepository.findByUsername("john")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(false);
            when(loginFailureRepository.countByUserIdAndAttemptedAtAfter(any(), any()))
                    .thenReturn(5L);

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);

            assertThatThrownBy(() -> service.login(command))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(loginFailureRepository).save(any(UserLoginFailureEntity.class));
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getLockedUntil())
                    .isEqualTo(FIXED_CLOCK.instant().plus(15, ChronoUnit.MINUTES));
        }

        // v1.1 #2 — 4 failures within the window (e.g. one already aged out from a
        // previous burst) does NOT lock. The repository count only includes rows
        // since (now - 10 minutes), so older rows simply don't appear in the count.
        @Test
        void failuresOutsideWindow_doNotCount() {
            UserEntity user = activeUser();
            LoginCommand command = new LoginCommand("john", "wrong_password");

            when(userRepository.findByUsername("john")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(false);
            // Simulated: 5 total failures historically, but only 4 within the
            // 10-minute window — the repository count returns 4, not 5.
            when(loginFailureRepository.countByUserIdAndAttemptedAtAfter(any(), any()))
                    .thenReturn(4L);

            assertThatThrownBy(() -> service.login(command))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(userRepository, never()).save(user);
            assertThat(user.getLockedUntil()).isNull();
        }

        @Test
        void lockedAccount_throwsAccountLocked() {
            UserEntity user = activeUser();
            user.setLockedUntil(FIXED_CLOCK.instant().plus(10, ChronoUnit.MINUTES));
            LoginCommand command = new LoginCommand("john", "any_password");

            when(userRepository.findByUsername("john")).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> service.login(command))
                    .isInstanceOf(AccountLockedException.class);
        }

        @Test
        void nonExistentUser_invokesPasswordEncoderMatchesForTimingDefence() {
            LoginCommand command = new LoginCommand("nonexistent", "password");

            when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.login(command))
                    .isInstanceOf(InvalidCredentialsException.class);

            // Verifies the dummy BCrypt comparison still runs so a non-existent
            // user takes the same ~100ms as a wrong-password attempt.
            verify(passwordEncoder).matches(eq("password"), any());
        }

        // v1.1 #2 — expired lockout: lockedUntil is cleared so the attempt isn't
        // rejected, but failure rows remain (they age out naturally).
        @Test
        void expiredLockout_isClearedOnNextAttempt() {
            UserEntity user = activeUser();
            // Lockout window expired 1 minute ago
            user.setLockedUntil(FIXED_CLOCK.instant().minus(1, ChronoUnit.MINUTES));
            LoginCommand command = new LoginCommand("john", "wrong_password");

            when(userRepository.findByUsername("john")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(any(), any())).thenReturn(false);
            // Failure rows have aged out of the window (lockout 15min > window 10min)
            when(loginFailureRepository.countByUserIdAndAttemptedAtAfter(any(), any()))
                    .thenReturn(1L);

            assertThatThrownBy(() -> service.login(command))
                    .isInstanceOf(InvalidCredentialsException.class);

            // lockedUntil was cleared by clearExpiredLockout()
            assertThat(user.getLockedUntil()).isNull();
        }

        // v1.1 #2 — a user with an expired lockout who logs in successfully
        // ends the call with lockedUntil = null. (An unexpired lockout would
        // throw AccountLockedException before the password check.)
        @Test
        void successfulLogin_withExpiredLockout_endsWithLockedUntilNull() {
            UserEntity user = activeUser();
            user.setLockedUntil(FIXED_CLOCK.instant().minus(1, ChronoUnit.MINUTES));
            LoginCommand command = new LoginCommand("john", "correct_password");

            when(userRepository.findByUsername("john")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("correct_password", "hashed_password")).thenReturn(true);
            when(jwtService.generateToken(any(), any())).thenReturn("jwt_token");
            when(jwtService.getExpiry(any())).thenReturn(Instant.parse("2026-01-18T10:00:00Z"));

            service.login(command);

            assertThat(user.getLockedUntil()).isNull();
        }
    }

    @Nested
    class Register {

        @Test
        void duplicateUser_throwsUserAlreadyExists() {
            RegisterCommand command = new RegisterCommand("john", "john@example.com", "password");

            when(userRepository.existsByUsernameOrEmail("john", "john@example.com"))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.register(command))
                    .isInstanceOf(UserAlreadyExistsException.class);
        }
    }

    @Nested
    class Logout {

        @Test
        void invalidToken_returnsSilently_withoutSavingRevocation() {
            String invalidToken = "totallyMadeUpString";

            when(jwtService.isValid(invalidToken)).thenReturn(false);

            service.logout(invalidToken);  // does not throw

            verifyNoInteractions(revokedTokenRepository);
        }
    }
}
