package com.finance.repository;

import com.finance.entity.ExpenseEntity;
import com.finance.entity.ExpenseId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseRepository extends JpaRepository<ExpenseEntity, ExpenseId> {

    // Single-expense lookup requires both id and date because of the composite PK on the partitioned table.
    @Query(value = "SELECT * FROM expenses WHERE id = :id AND expense_date = :date AND user_id = :userId AND deleted_at IS NULL",
           nativeQuery = true)
    Optional<ExpenseEntity> findActiveByIdAndDate(
            @Param("userId") UUID userId,
            @Param("id") UUID id,
            @Param("date") LocalDate date);
}
