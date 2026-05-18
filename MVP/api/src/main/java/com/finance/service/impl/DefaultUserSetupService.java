package com.finance.service.impl;

import com.finance.entity.BankAccountEntity;
import com.finance.repository.BankAccountRepository;
import com.finance.service.UserSetupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Creates the system bank accounts that every user needs before they can
// log their first expense. Called immediately after successful registration.
// CASH is the default for any expense where no bank account is specified.
@Service
public class DefaultUserSetupService implements UserSetupService {

    private final BankAccountRepository bankAccountRepository;

    public DefaultUserSetupService(BankAccountRepository bankAccountRepository) {
        this.bankAccountRepository = bankAccountRepository;
    }

    @Override
    @Transactional
    public void setupNewUser(UUID userId) {
        createSystemAccount(userId, "Cash",   "CASH");
        createSystemAccount(userId, "Crypto", "CRYPTO");
    }

    private void createSystemAccount(UUID userId, String name, String type) {
        BankAccountEntity account = new BankAccountEntity();
        account.setUserId(userId);
        account.setName(name);
        account.setAccountType(type);
        account.setSystem(true);
        bankAccountRepository.save(account);
    }
}
