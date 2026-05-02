package com.finance.repository;

import com.finance.entity.BankAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankAccountRepository extends JpaRepository<BankAccountEntity, UUID> {

    // Used by GET /bank/accounts — returns all active accounts for a user
    List<BankAccountEntity> findByUserIdAndStatus(UUID userId, String status);

    // Used by expense creation UI — needs to show all accounts including system ones
    List<BankAccountEntity> findByUserId(UUID userId);

    // Used to retrieve a specific system account type for a user
    // e.g. findByUserIdAndSystemAccountType(userId, "CASH")
    Optional<BankAccountEntity> findByUserIdAndSystemAccountType(UUID userId, String systemAccountType);
}