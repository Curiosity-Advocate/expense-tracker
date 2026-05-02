package com.finance.repository;

import com.finance.domain.ExpenseId;
import com.finance.entity.ExpenseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseRepository
        extends JpaRepository<ExpenseEntity, ExpenseId>,
                JpaSpecificationExecutor<ExpenseEntity> {

    // Used by idempotency check — looks up existing expense by composite key and user.
    // deletedAtIsNull ensures soft-deleted expenses are never returned as duplicates.
    Optional<ExpenseEntity> findByIdAndExpenseDateAndUserIdAndDeletedAtIsNull(
            UUID id, LocalDate expenseDate, UUID userId);
}