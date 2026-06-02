package com.finance.repository;

import com.finance.entity.BankAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BankAccountRepository extends JpaRepository<BankAccountEntity, UUID> {
    // Used by the expense service to resolve the user's CASH system account when
    // no bankAccountId is provided on expense creation.
    Optional<BankAccountEntity> findByUserIdAndAccountType(UUID userId, String accountType);

    // Used to verify an expense's requested bank_account_id actually belongs to
    // the user. FK validation alone is insufficient because PostgreSQL's
    // referential integrity checks bypass RLS — without this explicit check,
    // a user could attach another user's bank_account_id to their expenses.
    boolean existsByIdAndUserId(UUID id, UUID userId);
}
