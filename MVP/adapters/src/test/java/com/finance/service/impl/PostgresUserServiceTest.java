package com.finance.service.impl;

import com.finance.domain.UserProfile;
import com.finance.entity.UserEntity;
import com.finance.exception.UserNotFoundException;
import com.finance.repository.UserRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostgresUserServiceTest {

    @Mock UserRepository userRepository;

    @InjectMocks PostgresUserService service;

    @Nested
    class GetProfile {

        @Test
        void userNotFound_throwsUserNotFoundException() {
            UUID userId = UUID.randomUUID();

            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getProfile(userId))
                    .isInstanceOf(UserNotFoundException.class);
        }
    }

    @Nested
    class UpdateDiscoverability {

        @Test
        void savesNewDiscoverabilityValue() {
            UUID userId = UUID.randomUUID();
            boolean newValue = true;

            UserEntity user = new UserEntity();
            user.setUsername("john");
            user.setEmail("john@example.com");

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(any(UserEntity.class))).thenReturn(user);

            service.updateDiscoverability(userId, newValue);

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().isDiscoverable()).isTrue();
        }
    }
}
