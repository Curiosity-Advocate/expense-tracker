package com.finance.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import com.finance.repository.ExpenseSummaryRepository;

import static com.finance.repository.ExpenseSpecifications.*;

import com.finance.command.CreateExpenseCommand;
import com.finance.command.UpdateExpenseCommand;

import com.finance.domain.Expense;

import com.finance.entity.CategoryEntity;
import com.finance.entity.ExpenseCategoryEntity;
import com.finance.entity.ExpenseEntity;
import com.finance.entity.ExpenseIdempotencyKeyEntity;
import com.finance.entity.BankAccountEntity;

import com.finance.exception.DuplicateIdempotencyKeyException;
import com.finance.exception.ExpenseNotFoundException;
import com.finance.exception.FieldImmutableException;

import com.finance.repository.BankAccountRepository;
import com.finance.repository.CategoryRepository;
import com.finance.repository.ExpenseCategoryRepository;
import com.finance.repository.ExpenseIdempotencyKeyRepository;
import com.finance.repository.ExpenseRepository;

import com.finance.service.ExpenseService;
import com.finance.service.CategoryResolutionService;

import com.finance.domain.ExpensePage;
import com.finance.domain.ExpenseSource;
import com.finance.domain.ExpenseSummary;
import com.finance.domain.GroupBy;
import com.finance.domain.SummaryGroup;

import com.finance.query.SummaryQuery;
import com.finance.query.ExpenseQuery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import java.util.stream.Collectors;

@Service
public class PostgresExpenseService implements ExpenseService {

    private static final String UNCATEGORISED = "UNCATEGORISED";
    private static final String CASH          = "CASH";

    private final ExpenseRepository               expenseRepository;
    private final ExpenseCategoryRepository       expenseCategoryRepository;
    private final ExpenseIdempotencyKeyRepository idempotencyKeyRepository;
    private final CategoryRepository              categoryRepository;
    private final BankAccountRepository           bankAccountRepository;
    private final CategoryResolutionService categoryResolutionService;
    private final ExpenseSummaryRepository expenseSummaryRepository;

    public PostgresExpenseService(
            ExpenseRepository expenseRepository,
            ExpenseCategoryRepository expenseCategoryRepository,
            ExpenseIdempotencyKeyRepository idempotencyKeyRepository,
            CategoryRepository categoryRepository,
            BankAccountRepository bankAccountRepository,
            CategoryResolutionService categoryResolutionService,
            ExpenseSummaryRepository expenseSummaryRepository) {
        this.expenseRepository         = expenseRepository;
        this.expenseCategoryRepository = expenseCategoryRepository;
        this.idempotencyKeyRepository  = idempotencyKeyRepository;
        this.categoryRepository        = categoryRepository;
        this.bankAccountRepository     = bankAccountRepository;
        this.categoryResolutionService = categoryResolutionService;
        this.expenseSummaryRepository = expenseSummaryRepository;
    }

    @Override
    @Transactional
    public Expense createExpense(UUID userId, CreateExpenseCommand command) {

        // --- Idempotency check ---
        // If this idempotency key was already used by this user and hasn't expired,
        // return the original expense instead of creating a duplicate.
        if (command.idempotencyKey() != null) {
            var existing = idempotencyKeyRepository
                    .findByIdempotencyKeyAndUserIdAndExpiresAtAfter(
                            command.idempotencyKey(), userId, Instant.now());
            if (existing.isPresent()) {
                return expenseRepository
                        .findByIdAndExpenseDateAndUserIdAndDeletedAtIsNull(
                                existing.get().getExpenseId(),
                                existing.get().getExpenseDate(),
                                userId)
                        .map(this::toDomain)
                        .orElseThrow(() -> new DuplicateIdempotencyKeyException(command.idempotencyKey()));
            }
        }

        // --- Resolve bank account ---
        // If no bankAccountId provided, use the user's system CASH account.
        UUID bankAccountId = command.bankAccountId();
        if (bankAccountId == null) {
            bankAccountId = bankAccountRepository
                    .findByUserIdAndSystemAccountType(userId, CASH)
                    .map(BankAccountEntity::getId)
                    .orElseThrow(() -> new IllegalStateException(
                            "No CASH system account found for user " + userId));
        }

        // --- Resolve categories ---
        // Empty list → substitute UNCATEGORISED.
        List<String> categoryNames = (command.categories() == null || command.categories().isEmpty())
                ? List.of(UNCATEGORISED)
                : command.categories();

        List<CategoryEntity> resolvedCategories = categoryRepository
                .findByUserIdOrUserIdIsNull(userId)
                .stream()
                .filter(c -> categoryNames.contains(c.getName()))
                .toList();

        // --- Build and save expense ---
        ExpenseEntity expense = new ExpenseEntity();
        expense.setUserId(userId);
        expense.setAmount(command.amount());
        expense.setMerchantName(command.merchantName());
        expense.setExpenseDate(command.expenseDate());
        expense.setNotes(command.notes());
        expense.setPaymentMethod(command.paymentMethod());
        expense.setBankAccountId(bankAccountId);
        expense.setSource(ExpenseSource.MANUAL);
        expense.setIdempotencyKey(command.idempotencyKey());
        expenseRepository.save(expense);

        // --- Compute and save category weights ---
        // Even split: amount / number of categories, rounded to 2 decimal places.
        List<ExpenseCategoryEntity> categoryEntities = buildCategoryWeights(
                expense, resolvedCategories);
        expenseCategoryRepository.saveAll(categoryEntities);

        // --- Record idempotency key ---
        if (command.idempotencyKey() != null) {
            ExpenseIdempotencyKeyEntity key = new ExpenseIdempotencyKeyEntity();
            key.setIdempotencyKey(command.idempotencyKey());
            key.setUserId(userId);
            key.setExpenseId(expense.getId());
            key.setExpenseDate(expense.getExpenseDate());
            idempotencyKeyRepository.save(key);
        }

        return toDomain(expense, categoryEntities, resolvedCategories);
    }
    
    @Override
    @Transactional(readOnly = true)
    public ExpensePage queryExpenses(UUID userId, ExpenseQuery query) {
        
        // Spring Data pages are 0-indexed — subtract 1 from the 1-indexed API page
        Sort sort = Sort.by(
                "ASC".equalsIgnoreCase(query.sortOrder()) 
                ? Sort.Direction.ASC
                : Sort.Direction.DESC,
                query.sortBy()
        );

        Pageable pageable = PageRequest.of(query.page() - 1, query.pageSize(), sort);

        // Compose specification from non-null filters.
        // Null specifications are silently ignored by Specification.and()
        // so only filters the caller actually provided appear in the SQL.
        Specification<ExpenseEntity> spec = Specification
                .where(hasUserId(userId))
                .and(query.includeDeleted() ? null : isNotDeleted())
                .and(hasDateFrom(query.dateFrom()))
                .and(hasDateTo(query.dateTo()))
                .and(hasMerchantLike(query.merchantName()))
                .and(hasCategories(query.categories()))
                .and(hasPaymentMethod(query.paymentMethod()))
                .and(hasBankAccountId(query.bankAccountId()))
                .and(hasMinAmount(query.minAmount()))
                .and(hasMaxAmount(query.maxAmount()))
                .and(hasSource(query.source()));

        Page<ExpenseEntity> page = expenseRepository.findAll(spec, pageable);

        List<Expense> expenses = page.getContent()
                .stream()
                .map(this::toDomain)
                .toList();

        return new ExpensePage(
                expenses,
                query.page(),
                query.pageSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }    
    
    @Override
    @Transactional(readOnly = true)
    public Expense getExpense(UUID userId, UUID expenseId, LocalDate expenseDate) {
        return expenseRepository
        .findByIdAndExpenseDateAndUserIdAndDeletedAtIsNull(
                expenseId, expenseDate, userId)
        .map(this::toDomain)
        .orElseThrow(() -> new ExpenseNotFoundException(expenseId));
    }
    
    @Override
    @Transactional
    public Expense updateExpense(UUID userId, UUID expenseId, LocalDate expenseDate,UpdateExpenseCommand command) {
        ExpenseEntity expense = expenseRepository
        .findByIdAndExpenseDateAndUserIdAndDeletedAtIsNull(expenseId, expenseDate, userId)
        .orElseThrow(() -> new ExpenseNotFoundException(expenseId));

        enforceImmutabilityRules(expense, command);
        applyMutableFields(expense, command);

        expenseRepository.save(expense);

        if (command.categories() != null) {
                categoryResolutionService.replaceCategories(userId, expense.getId(), expense.getExpenseDate(), expense.getAmount(), command.categories());
        }

        return toDomain(expense);
    }

    @Override
    @Transactional
    public void softDeleteExpense(UUID userId, UUID expenseId, LocalDate expenseDate) {

        ExpenseEntity expense = expenseRepository
        .findByIdAndExpenseDateAndUserIdAndDeletedAtIsNull(expenseId, expenseDate, userId)
        .orElseThrow(() -> new ExpenseNotFoundException(expenseId));

        // Bank-imported expenses cannot be soft-deleted —
        // they are part of the immutable bank record.
        if (expense.getSource() == ExpenseSource.BANK_IMPORT) {
                throw new FieldImmutableException(
                        "expense",
                        "bank-imported expenses cannot be deleted");
        }

        expense.setDeletedAt(Instant.now());
        expenseRepository.save(expense);
    }
    
    @Override
    @Transactional(readOnly = true)
    public ExpenseSummary getSummary(UUID userId, SummaryQuery query) {

        // Fetch raw rows from the appropriate materialized view
        List<Map<String, Object>> rows = 
        switch (query.groupBy()) {
                case CATEGORY -> expenseSummaryRepository.summariseByCategory(
                        userId, query.dateFrom(), query.dateTo());
                case MERCHANT -> expenseSummaryRepository.summariseByMerchant(
                        userId, query.dateFrom(), query.dateTo());
                case MONTH    -> expenseSummaryRepository.summariseByMonth(
                        userId, query.dateFrom(), query.dateTo());
        };

        // Map raw rows to SummaryGroup domain objects
        List<SummaryGroup> groups = rows.stream()
                .map(row -> new SummaryGroup(
                        (String) row.get("group_key"),
                        (BigDecimal) row.get("total_amount"),
                        ((Number) row.get("transaction_count")).longValue(),
                        ((Number) row.get("percentage_of_total")).doubleValue()
                ))
                .toList();

        // Fetch grand total separately — covers edge case where
        // date range spans months with no transactions
        BigDecimal totalAmount = expenseSummaryRepository.getTotalAmount(
                userId, query.dateFrom(), query.dateTo());

        return new ExpenseSummary(
                totalAmount,
                query.dateFrom(),
                query.dateTo(),
                groups);
    }
    // --- Private helpers ---

    private void enforceImmutabilityRules(ExpenseEntity expense, UpdateExpenseCommand command) {
    
        if (expense.getSource() != ExpenseSource.BANK_IMPORT) return;
        
        if (command.amount() != null) 
                throw new FieldImmutableException("amount",
        "bank-imported expenses cannot be modified");
    
        if (command.merchantName() != null)
                throw new FieldImmutableException("merchantName",
                "bank-imported expenses cannot be modified");
    
        if (command.expenseDate() != null)
                throw new FieldImmutableException("expenseDate",
                "bank-imported expenses cannot be modified");
    
        if (command.paymentMethod() != null)
                throw new FieldImmutableException("paymentMethod",
                "bank-imported expenses cannot be modified");
    }

    private void applyMutableFields(ExpenseEntity expense, UpdateExpenseCommand command) {
    
        if (command.notes() != null) expense.setNotes(command.notes());

        if (expense.getSource() == ExpenseSource.BANK_IMPORT) return;
    
        if (command.amount() != null) expense.setAmount(command.amount());
    
        if (command.merchantName() != null) expense.setMerchantName(command.merchantName());
    
        if (command.expenseDate() != null) expense.setExpenseDate(command.expenseDate());
    
        if (command.paymentMethod() != null) expense.setPaymentMethod(command.paymentMethod());
    }

    private List<ExpenseCategoryEntity> buildCategoryWeights(
            ExpenseEntity expense,
            List<CategoryEntity> categories) {

        int count = categories.size();
        BigDecimal weight = expense.getAmount()
                .divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);

        List<ExpenseCategoryEntity> result = new ArrayList<>();
        for (CategoryEntity category : categories) {
            ExpenseCategoryEntity ec = new ExpenseCategoryEntity();
            ec.setExpenseId(expense.getId());
            ec.setExpenseDate(expense.getExpenseDate());
            ec.setCategoryId(category.getId());
            ec.setWeightAmount(weight);
            result.add(ec);
        }
        return result;
    }

    private Expense toDomain(ExpenseEntity entity) {
        List<ExpenseCategoryEntity> categories =
                expenseCategoryRepository.findByExpenseIdAndExpenseDate(
                        entity.getId(), entity.getExpenseDate());
        List<CategoryEntity> categoryEntities =
                categoryRepository.findByUserIdOrUserIdIsNull(entity.getUserId());
        return toDomain(entity, categories, categoryEntities);
    }

    private Expense toDomain(
            ExpenseEntity entity,
            List<ExpenseCategoryEntity> categoryWeights,
            List<CategoryEntity> allCategories) {

        // Build a lookup map from category ID to name
        Map<UUID, String> categoryNameMap = new HashMap<>();
        for (CategoryEntity c : allCategories) {
            categoryNameMap.put(c.getId(), c.getName());
        }

        List<String> categoryNames = new ArrayList<>();
        Map<String, BigDecimal> weights = new HashMap<>();

        for (ExpenseCategoryEntity ec : categoryWeights) {
            String name = categoryNameMap.get(ec.getCategoryId());
            if (name != null) {
                categoryNames.add(name);
                weights.put(name, ec.getWeightAmount());
            }
        }

        return new Expense(
                entity.getId(),
                entity.getExpenseDate(),
                entity.getAmount(),
                entity.getMerchantName(),
                categoryNames,
                weights,
                entity.getNotes(),
                entity.getPaymentMethod(),
                entity.getBankAccountId(),
                entity.getSource(),
                entity.getBankStatus(),
                entity.isAiCategorised(),
                entity.isMerged(),
                entity.getCreatedAt()
        );
    }
}