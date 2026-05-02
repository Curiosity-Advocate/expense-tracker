package com.finance.repository;

import com.finance.entity.ExpenseCategoryEntity;
import com.finance.entity.ExpenseEntity;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Static factory methods — one per filter.
// Each method returns a Specification<ExpenseEntity> (a predicate)
// or null if the input is null.
// Null specifications are silently ignored by Specification.and() —
// so callers chain everything and only non-null filters appear in the SQL.
public class ExpenseSpecifications {

    // Private constructor — this is a utility class, never instantiated.
    private ExpenseSpecifications() {}

    // Always applied — every criteriaQuery is scoped to the authenticated user.
    // This is Layer 2 of the three-layer RLS defence.
    public static Specification<ExpenseEntity> hasUserId(UUID userId) {
        return (expenseRoot, criteriaQuery, builder) ->
                builder.equal(expenseRoot.get("userId"), userId);
    }

    // Applied unless includeDeleted = true.
    // Excludes soft-deleted expenses from results.
    public static Specification<ExpenseEntity> isNotDeleted() {
        return (expenseRoot, criteriaQuery, builder) ->
                builder.isNull(expenseRoot.get("deletedAt"));
    }

    // WHERE expense_date >= :dateFrom
    public static Specification<ExpenseEntity> hasDateFrom(LocalDate dateFrom) {
        if (dateFrom == null) return null;
        return (expenseRoot, criteriaQuery, builder) ->
                builder.greaterThanOrEqualTo(expenseRoot.get("expenseDate"), dateFrom);
    }

    // WHERE expense_date <= :dateTo
    public static Specification<ExpenseEntity> hasDateTo(LocalDate dateTo) {
        if (dateTo == null) return null;
        return (expenseRoot, criteriaQuery, builder) ->
                builder.lessThanOrEqualTo(expenseRoot.get("expenseDate"), dateTo);
    }

    // WHERE LOWER(merchant_name) LIKE '%:merchantName%'
    // Case-insensitive partial match.
    public static Specification<ExpenseEntity> hasMerchantLike(String merchantName) {
        if (merchantName == null || merchantName.isBlank()) return null;
        return (expenseRoot, criteriaQuery, builder) ->
                builder.like(
                    builder.lower(expenseRoot.get("merchantName")),
                    "%" + merchantName.toLowerCase() + "%");
    }

    // WHERE payment_method = :paymentMethod
    public static Specification<ExpenseEntity> hasPaymentMethod(String paymentMethod) {
        if (paymentMethod == null) return null;
        return (expenseRoot, criteriaQuery, builder) ->
                builder.equal(expenseRoot.get("paymentMethod"), paymentMethod);
    }

    // WHERE bank_account_id = :bankAccountId
    public static Specification<ExpenseEntity> hasBankAccountId(UUID bankAccountId) {
        if (bankAccountId == null) return null;
        return (expenseRoot, criteriaQuery, builder) ->
                builder.equal(expenseRoot.get("bankAccountId"), bankAccountId);
    }

    // WHERE amount >= :minAmount
    public static Specification<ExpenseEntity> hasMinAmount(BigDecimal minAmount) {
        if (minAmount == null) return null;
        return (expenseRoot, criteriaQuery, builder) ->
                builder.greaterThanOrEqualTo(expenseRoot.get("amount"), minAmount);
    }

    // WHERE amount <= :maxAmount
    public static Specification<ExpenseEntity> hasMaxAmount(BigDecimal maxAmount) {
        if (maxAmount == null) return null;
        return (expenseRoot, criteriaQuery, builder) ->
                builder.lessThanOrEqualTo(expenseRoot.get("amount"), maxAmount);
    }

    // WHERE source = :source
    public static Specification<ExpenseEntity> hasSource(String source) {
        if (source == null) return null;
        return (expenseRoot, criteriaQuery, builder) ->
                builder.equal(expenseRoot.get("source"), source);
    }

    // WHERE EXISTS (
    //     SELECT ec.expense_id FROM expense_categories ec
    //     INNER JOIN categories c ON c.id = ec.category_id
    //     WHERE ec.expense_id = expenses.id
    //       AND ec.expense_date = expenses.expense_date
    //       AND c.name IN (:categories)
    // )
    // Matches expenses that have at least one of the requested categories.
    public static Specification<ExpenseEntity> hasCategories(List<String> categories) {
        if (categories == null || categories.isEmpty()) return null;
        return (expenseRoot, criteriaQuery, builder) -> {
            Subquery<UUID> subquery = criteriaQuery.subquery(UUID.class);
            var ecRoot = subquery.from(ExpenseCategoryEntity.class);
            var categoryJoin = ecRoot.join("category", JoinType.INNER);

            subquery.select(ecRoot.get("expenseId"))
                    .where(builder.and(
                            builder.equal(ecRoot.get("expenseId"), expenseRoot.get("id")),
                            builder.equal(ecRoot.get("expenseDate"), expenseRoot.get("expenseDate")),
                            categoryJoin.get("name").in(categories)
                    ));

            return builder.exists(subquery);
        };
    }
}