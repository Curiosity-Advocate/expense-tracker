package com.finance.repository;

import com.finance.entity.BankAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BankAccountRepository extends JpaRepository<BankAccountEntity, UUID> {
    // Used by the expense service to resolve the user's CASH system account when
    // no bankAccountId is provided on expense creation.
    Optional<BankAccountEntity> findByUserIdAndAccountType(UUID userId, String accountType);
}
