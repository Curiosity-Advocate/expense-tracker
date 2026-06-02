package com.finance.service.impl;

import com.finance.service.ExpenseService;
import com.finance.command.CreateExpenseCommand;
import com.finance.command.UpdateExpenseCommand;
import com.finance.domain.*;
import com.finance.entity.*;
import com.finance.exception.*;
import com.finance.query.ExpenseQuery;
import com.finance.query.SummaryQuery;
import com.finance.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class PostgresExpenseService implements ExpenseService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ExpenseRepository expenseRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final ExpenseIdempotencyKeyRepository idempotencyKeyRepository;
    private final CategoryRepository categoryRepository;
    private final BankAccountRepository bankAccountRepository;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public PostgresExpenseService(ExpenseRepository expenseRepository,
                                  ExpenseCategoryRepository expenseCategoryRepository,
                                  ExpenseIdempotencyKeyRepository idempotencyKeyRepository,
                                  CategoryRepository categoryRepository,
                                  BankAccountRepository bankAccountRepository,
                                  Clock clock) {
        this.expenseRepository = expenseRepository;
        this.expenseCategoryRepository = expenseCategoryRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.categoryRepository = categoryRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.clock = clock;
    }

    @Override
    public Expense createExpense(UUID userId, CreateExpenseCommand cmd) {
        String keyStr = cmd.idempotencyKey().toString();
        if (idempotencyKeyRepository.existsById(new ExpenseIdempotencyKeyId(userId, keyStr))) {
            throw new DuplicateIdempotencyKeyException(keyStr);
        }

        List<CategoryEntity> resolvedCategories = resolveCategories(userId, cmd.categories());
        Map<String, BigDecimal> weights = computeWeights(cmd.amount(), cmd.categories(), cmd.categoryWeights());

        UUID bankAccountId = resolveBankAccount(userId, cmd.bankAccountId());

        ExpenseId expenseId = new ExpenseId(UUID.randomUUID(), cmd.expenseDate());
        ExpenseEntity expense = new ExpenseEntity();
        expense.setId(expenseId);
        expense.setUserId(userId);
        expense.setAmount(cmd.amount());
        expense.setMerchantName(cmd.merchantName());
        expense.setPaymentMethod(cmd.paymentMethod() != null ? cmd.paymentMethod() : "OTHER");
        expense.setBankAccountId(bankAccountId);
        expense.setNotes(cmd.notes());
        expense.setSource(ExpenseSource.MANUAL.name());
        expenseRepository.save(expense);

        saveCategoryRows(userId, expenseId, resolvedCategories, weights);

        idempotencyKeyRepository.save(new ExpenseIdempotencyKeyEntity(
                new ExpenseIdempotencyKeyId(userId, keyStr),
                expenseId.getId(),
                expenseId.getExpenseDate()));

        return toDomain(expense, resolvedCategories, weights);
    }

    @Override
    @Transactional(readOnly = true)
    public Expense getExpense(UUID userId, UUID expenseId, LocalDate expenseDate) {
        ExpenseEntity expense = expenseRepository.findActiveByIdAndDate(userId, expenseId, expenseDate)
                .orElseThrow(() -> new ExpenseNotFoundException(expenseId));
        return toDomainWithCategories(expense);
    }

    @Override
    public Expense updateExpense(UUID userId, UUID expenseId, LocalDate expenseDate,
                                  UpdateExpenseCommand cmd) {
        ExpenseEntity expense = expenseRepository.findActiveByIdAndDate(userId, expenseId, expenseDate)
                .orElseThrow(() -> new ExpenseNotFoundException(expenseId));

        if (ExpenseSource.BANK_IMPORT.name().equals(expense.getSource())) {
            if (cmd.amount() != null) throw new FieldImmutableException("amount");
            if (cmd.merchantName() != null) throw new FieldImmutableException("merchantName");
            if (cmd.paymentMethod() != null) throw new FieldImmutableException("paymentMethod");
        }

        if (cmd.amount() != null) expense.setAmount(cmd.amount());
        if (cmd.merchantName() != null) expense.setMerchantName(cmd.merchantName());
        if (cmd.paymentMethod() != null) expense.setPaymentMethod(cmd.paymentMethod());
        if (cmd.notes() != null) expense.setNotes(cmd.notes());

        List<CategoryEntity> resolvedCategories;
        Map<String, BigDecimal> weights;
        if (cmd.categories() != null && !cmd.categories().isEmpty()) {
            resolvedCategories = resolveCategories(userId, cmd.categories());
            weights = computeWeights(expense.getAmount(), cmd.categories(), cmd.categoryWeights());
            expenseCategoryRepository.deleteByExpense(expenseId, expenseDate);
            saveCategoryRows(userId, expense.getId(), resolvedCategories, weights);
        } else {
            resolvedCategories = loadCategoryEntities(expenseId, expenseDate);
            weights = loadWeights(expenseId, expenseDate, resolvedCategories);
        }

        expenseRepository.save(expense);
        return toDomain(expense, resolvedCategories, weights);
    }

    @Override
    public void softDeleteExpense(UUID userId, UUID expenseId, LocalDate expenseDate) {
        ExpenseEntity expense = expenseRepository.findActiveByIdAndDate(userId, expenseId, expenseDate)
                .orElseThrow(() -> new ExpenseNotFoundException(expenseId));
        expense.setDeletedAt(Instant.now(clock));
        expenseRepository.save(expense);
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public ExpensePage queryExpenses(UUID userId, ExpenseQuery q) {
        int pageSize = Math.min(q.pageSize() > 0 ? q.pageSize() : 20, MAX_PAGE_SIZE);
        int page = q.page() > 0 ? q.page() : 1;
        int offset = (page - 1) * pageSize;

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", userId);
        String filter = buildExpenseFilter(userId, q, params);

        String sortCol = "expense_date".equals(q.sortBy()) ? "e.expense_date" : "e.created_at";
        String sortDir = "asc".equalsIgnoreCase(q.sortOrder()) ? "ASC" : "DESC";

        Query countQ = entityManager.createNativeQuery(
                "SELECT COUNT(DISTINCT e.id) FROM expenses e " + filter);
        params.forEach(countQ::setParameter);
        long total = ((Number) countQ.getSingleResult()).longValue();

        params.put("limit", pageSize);
        params.put("offset", offset);
        Query dataQ = entityManager.createNativeQuery(
                "SELECT DISTINCT e.* FROM expenses e " + filter
                + " ORDER BY " + sortCol + " " + sortDir
                + " LIMIT :limit OFFSET :offset",
                ExpenseEntity.class);
        params.forEach(dataQ::setParameter);
        List<ExpenseEntity> rows = dataQ.getResultList();

        List<Expense> data = rows.stream().map(this::toDomainWithCategories).toList();
        int totalPages = (int) Math.ceil((double) total / pageSize);
        return new ExpensePage(data, page, pageSize, total, totalPages);
    }

    // Package-private so CountQueryBuilding tests can verify the filter string directly.
    // java:S1192 — SQL named parameter literals (:dateFrom etc.) are coupled to inline SQL
    // strings; extracting as constants would hurt readability without adding safety.
    @SuppressWarnings("java:S1192")
    static String buildExpenseFilter(UUID userId, ExpenseQuery q, Map<String, Object> params) {
        StringBuilder filter = new StringBuilder(
                "LEFT JOIN expense_categories ec ON e.id = ec.expense_id AND e.expense_date = ec.expense_date " +
                "LEFT JOIN categories c ON ec.category_id = c.id " +
                "WHERE e.user_id = :userId");

        if (!q.includeDeleted()) filter.append(" AND e.deleted_at IS NULL");
        if (q.dateFrom() != null)      { filter.append(" AND e.expense_date >= :dateFrom"); params.put("dateFrom", q.dateFrom()); }
        if (q.dateTo() != null)        { filter.append(" AND e.expense_date <= :dateTo");   params.put("dateTo",   q.dateTo()); }
        if (q.merchantName() != null)  { filter.append(" AND e.merchant_name ILIKE :merchant"); params.put("merchant", "%" + q.merchantName() + "%"); }
        if (q.minAmount() != null)     { filter.append(" AND e.amount >= :minAmt"); params.put("minAmt", q.minAmount()); }
        if (q.maxAmount() != null)     { filter.append(" AND e.amount <= :maxAmt"); params.put("maxAmt", q.maxAmount()); }
        if (q.paymentMethod() != null) { filter.append(" AND e.payment_method = :pm");      params.put("pm",     q.paymentMethod()); }
        if (q.bankAccountId() != null) { filter.append(" AND e.bank_account_id = :baid");   params.put("baid",   q.bankAccountId()); }
        if (q.source() != null)        { filter.append(" AND e.source = :source");          params.put("source", q.source()); }
        if (q.categories() != null && !q.categories().isEmpty()) {
            filter.append(" AND c.name = ANY(:cats)");
            params.put("cats", q.categories().toArray(new String[0]));
        }

        return filter.toString();
    }

    @Override
    @Transactional(readOnly = true)
    // java:S1192 suppressed for the same reason as queryExpenses above.
    @SuppressWarnings({"unchecked", "java:S1192"})
    public ExpenseSummary getSummary(UUID userId, SummaryQuery q) {
        List<SummaryGroup> groups;
        BigDecimal grandTotal;

        if (q.groupBy() == GroupBy.MERCHANT) {
            String sql = "SELECT merchant_name, total_amount, transaction_count FROM v_merchant_summary " +
                         "WHERE (:dateFrom IS NULL OR (period_year * 100 + period_month) >= " +
                         "   (EXTRACT(YEAR FROM CAST(:dateFrom AS DATE))::INT * 100 + EXTRACT(MONTH FROM CAST(:dateFrom AS DATE))::INT)) " +
                         "AND (:dateTo IS NULL OR (period_year * 100 + period_month) <= " +
                         "   (EXTRACT(YEAR FROM CAST(:dateTo AS DATE))::INT * 100 + EXTRACT(MONTH FROM CAST(:dateTo AS DATE))::INT))";
            Query nq = entityManager.createNativeQuery(sql);
            nq.setParameter("dateFrom", q.dateFrom());
            nq.setParameter("dateTo", q.dateTo());
            List<Object[]> rows = nq.getResultList();
            grandTotal = rows.stream().map(r -> (BigDecimal) r[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
            final BigDecimal gt = grandTotal;
            groups = rows.stream().map(r -> new SummaryGroup(
                    (String) r[0],
                    (BigDecimal) r[1],
                    ((Number) r[2]).longValue(),
                    gt.compareTo(BigDecimal.ZERO) == 0 ? 0 : ((BigDecimal) r[1]).divide(gt, 4, RoundingMode.HALF_EVEN).doubleValue() * 100
            )).toList();

        } else if (q.groupBy() == GroupBy.CATEGORY) {
            String sql = "SELECT c.name, SUM(mv.total_amount), SUM(mv.transaction_count) " +
                         "FROM v_monthly_expense_summary mv JOIN categories c ON mv.category_id = c.id " +
                         "WHERE (:dateFrom IS NULL OR (mv.period_year * 100 + mv.period_month) >= " +
                         "   (EXTRACT(YEAR FROM CAST(:dateFrom AS DATE))::INT * 100 + EXTRACT(MONTH FROM CAST(:dateFrom AS DATE))::INT)) " +
                         "AND (:dateTo IS NULL OR (mv.period_year * 100 + mv.period_month) <= " +
                         "   (EXTRACT(YEAR FROM CAST(:dateTo AS DATE))::INT * 100 + EXTRACT(MONTH FROM CAST(:dateTo AS DATE))::INT)) " +
                         "GROUP BY c.name ORDER BY SUM(mv.total_amount) DESC";
            Query nq = entityManager.createNativeQuery(sql);
            nq.setParameter("dateFrom", q.dateFrom());
            nq.setParameter("dateTo", q.dateTo());
            List<Object[]> rows = nq.getResultList();
            grandTotal = rows.stream().map(r -> (BigDecimal) r[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
            final BigDecimal gt = grandTotal;
            groups = rows.stream().map(r -> new SummaryGroup(
                    (String) r[0],
                    (BigDecimal) r[1],
                    ((Number) r[2]).longValue(),
                    gt.compareTo(BigDecimal.ZERO) == 0 ? 0 : ((BigDecimal) r[1]).divide(gt, 4, RoundingMode.HALF_EVEN).doubleValue() * 100
            )).toList();

        } else { // MONTH
            String sql = "SELECT period_year, period_month, SUM(total_amount), SUM(transaction_count) " +
                         "FROM v_monthly_expense_summary " +
                         "WHERE (:dateFrom IS NULL OR (period_year * 100 + period_month) >= " +
                         "   (EXTRACT(YEAR FROM CAST(:dateFrom AS DATE))::INT * 100 + EXTRACT(MONTH FROM CAST(:dateFrom AS DATE))::INT)) " +
                         "AND (:dateTo IS NULL OR (period_year * 100 + period_month) <= " +
                         "   (EXTRACT(YEAR FROM CAST(:dateTo AS DATE))::INT * 100 + EXTRACT(MONTH FROM CAST(:dateTo AS DATE))::INT)) " +
                         "GROUP BY period_year, period_month ORDER BY period_year DESC, period_month DESC";
            Query nq = entityManager.createNativeQuery(sql);
            nq.setParameter("dateFrom", q.dateFrom());
            nq.setParameter("dateTo", q.dateTo());
            List<Object[]> rows = nq.getResultList();
            grandTotal = rows.stream().map(r -> (BigDecimal) r[2]).reduce(BigDecimal.ZERO, BigDecimal::add);
            final BigDecimal gt = grandTotal;
            groups = rows.stream().map(r -> {
                String key = String.format("%d-%02d", ((Number) r[0]).intValue(), ((Number) r[1]).intValue());
                BigDecimal amt = (BigDecimal) r[2];
                return new SummaryGroup(key, amt, ((Number) r[3]).longValue(),
                        gt.compareTo(BigDecimal.ZERO) == 0 ? 0 : amt.divide(gt, 4, RoundingMode.HALF_EVEN).doubleValue() * 100);
            }).toList();
        }

        return new ExpenseSummary(grandTotal, q.dateFrom(), q.dateTo(), groups);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<CategoryEntity> resolveCategories(UUID userId, List<String> names) {
        return names.stream()
                .map(name -> categoryRepository.findByNameVisibleToUser(userId, name)
                        .orElseThrow(() -> new CategoryNotFoundException(name)))
                .toList();
    }

    private Map<String, BigDecimal> computeWeights(BigDecimal amount, List<String> names,
                                                    Map<String, BigDecimal> provided) {
        if (provided == null || provided.isEmpty()) {
            BigDecimal even = amount.divide(BigDecimal.valueOf(names.size()), 2, RoundingMode.HALF_EVEN);
            return names.stream().collect(Collectors.toMap(n -> n, n -> even));
        }
        // Every category must have an explicit weight entry — missing entries would
        // silently save weight_amount=0, which is undetectable data corruption.
        for (String name : names) {
            if (!provided.containsKey(name)) {
                throw new InvalidCategoryWeightsException(
                        "No weight provided for category: " + name);
            }
        }
        // Validate provided weights sum to amount (±1 cent tolerance)
        BigDecimal sum = provided.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.subtract(amount).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new InvalidCategoryWeightsException(
                    "Category weights must sum to the expense amount (" + amount + "), got " + sum);
        }
        return provided;
    }

    private UUID resolveBankAccount(UUID userId, UUID requested) {
        if (requested != null) {
            if (!bankAccountRepository.existsByIdAndUserId(requested, userId)) {
                throw new BankAccountNotFoundException(requested);
            }
            return requested;
        }
        return bankAccountRepository.findByUserIdAndAccountType(userId, "CASH")
                .map(BankAccountEntity::getId)
                .orElseThrow(() -> new IllegalStateException("No CASH account found for user " + userId));
    }

    private void saveCategoryRows(UUID userId, ExpenseId expenseId,
                                   List<CategoryEntity> cats, Map<String, BigDecimal> weights) {
        for (CategoryEntity cat : cats) {
            BigDecimal w = weights.getOrDefault(cat.getName(), BigDecimal.ZERO);
            expenseCategoryRepository.save(new ExpenseCategoryEntity(
                    new ExpenseCategoryId(expenseId.getId(), expenseId.getExpenseDate(), cat.getId()),
                    userId, w));
        }
    }

    private List<CategoryEntity> loadCategoryEntities(UUID expenseId, LocalDate expenseDate) {
        return expenseCategoryRepository.findByExpense(expenseId, expenseDate).stream()
                .map(ec -> categoryRepository.findById(ec.getId().getCategoryId()).orElseThrow())
                .toList();
    }

    private Map<String, BigDecimal> loadWeights(UUID expenseId, LocalDate expenseDate,
                                                 List<CategoryEntity> cats) {
        List<ExpenseCategoryEntity> rows = expenseCategoryRepository.findByExpense(expenseId, expenseDate);
        Map<UUID, BigDecimal> byId = rows.stream()
                .collect(Collectors.toMap(ec -> ec.getId().getCategoryId(), ExpenseCategoryEntity::getWeightAmount));
        return cats.stream().collect(Collectors.toMap(CategoryEntity::getName, c -> byId.getOrDefault(c.getId(), BigDecimal.ZERO)));
    }

    private Expense toDomainWithCategories(ExpenseEntity e) {
        List<CategoryEntity> cats = loadCategoryEntities(e.getId().getId(), e.getId().getExpenseDate());
        Map<String, BigDecimal> weights = loadWeights(e.getId().getId(), e.getId().getExpenseDate(), cats);
        return toDomain(e, cats, weights);
    }

    private Expense toDomain(ExpenseEntity e, List<CategoryEntity> cats, Map<String, BigDecimal> weights) {
        return new Expense(
                e.getId().getId(),
                e.getId().getExpenseDate(),
                e.getAmount(),
                e.getMerchantName(),
                cats.stream().map(CategoryEntity::getName).toList(),
                weights,
                e.getNotes(),
                e.getPaymentMethod(),
                e.getBankAccountId(),
                ExpenseSource.valueOf(e.getSource()),
                e.isAiCategorised(),
                e.getCreatedAt());
    }
}
