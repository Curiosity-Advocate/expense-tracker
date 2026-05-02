package com.finance.repository;

import com.finance.entity.ExpenseCategoryEntity;
import com.finance.entity.ExpenseCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategoryEntity, ExpenseCategoryId> {

    List<ExpenseCategoryEntity> findByExpenseIdAndExpenseDate(UUID expenseId, LocalDate expenseDate);

    int countByExpenseIdAndExpenseDate(UUID expenseId, LocalDate expenseDate);
}