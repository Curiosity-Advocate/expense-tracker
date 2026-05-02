package com.finance.service.impl;

import com.finance.domain.SystemAccountType;
import com.finance.entity.BankAccountEntity;
import com.finance.repository.BankAccountRepository;
import com.finance.service.UserSetupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DefaultUserSetupService implements UserSetupService {

    private final BankAccountRepository bankAccountRepository;

    public DefaultUserSetupService(BankAccountRepository bankAccountRepository) {
        this.bankAccountRepository = bankAccountRepository;
    }

    @Override
    @Transactional
    public void setupNewUser(UUID userId) {
        createSystemAccount(userId, SystemAccountType.CASH);
        createSystemAccount(userId, SystemAccountType.CRYPTO);
    }

    private void createSystemAccount(UUID userId, SystemAccountType type) {
        BankAccountEntity account = new BankAccountEntity();
        account.setUserId(userId);
        account.setInstitutionName(type.getDisplayName());
        account.setAccountName(type.getDisplayName());
        account.setSystemAccount(true);
        account.setSystemAccountType(type.name());
        account.setStatus("ACTIVE");
        bankAccountRepository.save(account);
    }
}