package com.finance.repository;

import com.finance.entity.ExpenseCategoryEntity;
import com.finance.entity.ExpenseCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategoryEntity, ExpenseCategoryId> {

    @Query("SELECT ec FROM ExpenseCategoryEntity ec WHERE ec.id.expenseId = :expenseId AND ec.id.expenseDate = :expenseDate")
    List<ExpenseCategoryEntity> findByExpense(@Param("expenseId") UUID expenseId,
                                              @Param("expenseDate") LocalDate expenseDate);

    @Modifying
    @Query("DELETE FROM ExpenseCategoryEntity ec WHERE ec.id.expenseId = :expenseId AND ec.id.expenseDate = :expenseDate")
    void deleteByExpense(@Param("expenseId") UUID expenseId,
                         @Param("expenseDate") LocalDate expenseDate);
}
