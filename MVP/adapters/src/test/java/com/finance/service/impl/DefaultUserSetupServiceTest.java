package com.finance.service.impl;

import com.finance.entity.BankAccountEntity;
import com.finance.repository.BankAccountRepository;
import com.finance.security.RoleElevationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DefaultUserSetupServiceTest {

    @Mock BankAccountRepository bankAccountRepository;
    @Mock RoleElevationService roleElevationService;

    @InjectMocks DefaultUserSetupService service;

    @Test
    void setupNewUser_savesCashAndCryptoBankAccounts() {
        UUID userId = UUID.randomUUID();

        service.setupNewUser(userId);

        ArgumentCaptor<BankAccountEntity> captor = ArgumentCaptor.forClass(BankAccountEntity.class);
        verify(bankAccountRepository, times(2)).save(captor.capture());

        List<BankAccountEntity> saved = captor.getAllValues();
        assertThat(saved).extracting(BankAccountEntity::getName)
                .containsExactlyInAnyOrder("Cash", "Crypto");
    }
}
