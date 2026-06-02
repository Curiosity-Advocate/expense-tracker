package com.finance.service.impl;

import com.finance.query.ExpenseQuery;
import com.finance.command.CreateExpenseCommand;
import com.finance.command.UpdateExpenseCommand;
import com.finance.domain.ExpenseSource;
import com.finance.entity.CategoryEntity;
import com.finance.entity.ExpenseEntity;
import com.finance.entity.ExpenseIdempotencyKeyId;
import com.finance.exception.BankAccountNotFoundException;
import com.finance.exception.CategoryNotFoundException;
import com.finance.exception.DuplicateIdempotencyKeyException;
import com.finance.exception.ExpenseNotFoundException;
import com.finance.exception.FieldImmutableException;
import com.finance.exception.InvalidCategoryWeightsException;
import com.finance.repository.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostgresExpenseServiceTest {

    @Mock ExpenseRepository expenseRepository;
    @Mock ExpenseCategoryRepository expenseCategoryRepository;
    @Mock ExpenseIdempotencyKeyRepository idempotencyKeyRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock BankAccountRepository bankAccountRepository;
    @Mock Clock clock;

    // EntityManager is @PersistenceContext — null in unit tests.
    // None of these tests reach queryExpenses or getSummary which use it.
    @InjectMocks PostgresExpenseService service;

    @Nested
    class CreateExpense {

        @Test
        void duplicateIdempotencyKey_throwsDuplicateIdempotencyKeyException() {
            UUID userId = UUID.randomUUID();
            UUID idempotencyKey = UUID.randomUUID();
            LocalDate expenseDate = LocalDate.of(2026, 1, 11);
            CreateExpenseCommand cmd = new CreateExpenseCommand(
                    idempotencyKey, new BigDecimal("100.00"), "Supermarket",
                    expenseDate, List.of("Groceries"), null, null, null, null);

            when(idempotencyKeyRepository.existsById(any(ExpenseIdempotencyKeyId.class)))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.createExpense(userId, cmd))
                    .isInstanceOf(DuplicateIdempotencyKeyException.class);
        }

        @Test
        void weightsDoNotSumToAmount_throwsInvalidCategoryWeightsException() {
            UUID userId = UUID.randomUUID();
            LocalDate expenseDate = LocalDate.of(2026, 1, 11);
            // amount=$100 but provided weight for Groceries is only $90
            CreateExpenseCommand cmd = new CreateExpenseCommand(
                    UUID.randomUUID(), new BigDecimal("100.00"), "Supermarket",
                    expenseDate, List.of("Groceries"),
                    Map.of("Groceries", new BigDecimal("90.00")),
                    null, null, null);

            when(idempotencyKeyRepository.existsById(any())).thenReturn(false);
            when(categoryRepository.findByNameVisibleToUser(userId, "Groceries"))
                    .thenReturn(Optional.of(categoryNamed("Groceries")));

            assertThatThrownBy(() -> service.createExpense(userId, cmd))
                    .isInstanceOf(InvalidCategoryWeightsException.class);
        }

        @Test
        void foreignBankAccount_throwsBankAccountNotFoundException() {
            UUID userId = UUID.randomUUID();
            UUID foreignBankAccountId = UUID.randomUUID();
            LocalDate expenseDate = LocalDate.of(2026, 1, 11);
            CreateExpenseCommand cmd = new CreateExpenseCommand(
                    UUID.randomUUID(), new BigDecimal("100.00"), "Supermarket",
                    expenseDate, List.of("Groceries"),
                    null, null, null, foreignBankAccountId);

            when(idempotencyKeyRepository.existsById(any())).thenReturn(false);
            when(categoryRepository.findByNameVisibleToUser(userId, "Groceries"))
                    .thenReturn(Optional.of(categoryNamed("Groceries")));
            when(bankAccountRepository.existsByIdAndUserId(foreignBankAccountId, userId))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.createExpense(userId, cmd))
                    .isInstanceOf(BankAccountNotFoundException.class);
        }

        @Test
        void unknownCategory_throwsCategoryNotFoundExceptionContainingName() {
            UUID userId = UUID.randomUUID();
            LocalDate expenseDate = LocalDate.of(2026, 1, 11);
            CreateExpenseCommand cmd = new CreateExpenseCommand(
                    UUID.randomUUID(), new BigDecimal("100.00"), "Supermarket",
                    expenseDate, List.of("UnknownCategory"),
                    null, null, null, null);

            when(idempotencyKeyRepository.existsById(any())).thenReturn(false);
            when(categoryRepository.findByNameVisibleToUser(userId, "UnknownCategory"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createExpense(userId, cmd))
                    .isInstanceOf(CategoryNotFoundException.class)
                    .hasMessageContaining("UnknownCategory");
        }

        // Bug #1.5a — silent zero-weight when a category is missing from provided weights.
        // names=["Groceries","Transport"], weights={"Groceries":100.00} — sum check passes,
        // but Transport has no entry; the service must reject rather than silently save $0.
        @Test
        void missingWeightForOneCategory_throwsInvalidCategoryWeightsException() {
            UUID userId = UUID.randomUUID();
            LocalDate expenseDate = LocalDate.of(2026, 1, 11);
            CreateExpenseCommand cmd = new CreateExpenseCommand(
                    UUID.randomUUID(), new BigDecimal("100.00"), "Supermarket",
                    expenseDate, List.of("Groceries", "Transport"),
                    Map.of("Groceries", new BigDecimal("100.00")), // Transport missing
                    null, null, null);

            when(idempotencyKeyRepository.existsById(any())).thenReturn(false);
            when(categoryRepository.findByNameVisibleToUser(userId, "Groceries"))
                    .thenReturn(Optional.of(categoryNamed("Groceries")));
            when(categoryRepository.findByNameVisibleToUser(userId, "Transport"))
                    .thenReturn(Optional.of(categoryNamed("Transport")));

            assertThatThrownBy(() -> service.createExpense(userId, cmd))
                    .isInstanceOf(InvalidCategoryWeightsException.class)
                    .hasMessageContaining("Transport");
        }
    }

    @Nested
    class UpdateExpense {

        @Test
        void bankImportExpense_throwsFieldImmutableException_whenAmountChanged() {
            UUID userId = UUID.randomUUID();
            UUID expenseId = UUID.randomUUID();
            LocalDate expenseDate = LocalDate.of(2026, 1, 11);

            ExpenseEntity bankImportExpense = new ExpenseEntity();
            bankImportExpense.setSource(ExpenseSource.BANK_IMPORT.name());

            UpdateExpenseCommand cmd = new UpdateExpenseCommand(
                    new BigDecimal("150.00"), null, null, null, null, null, null);

            when(expenseRepository.findActiveByIdAndDate(userId, expenseId, expenseDate))
                    .thenReturn(Optional.of(bankImportExpense));

            assertThatThrownBy(() -> service.updateExpense(userId, expenseId, expenseDate, cmd))
                    .isInstanceOf(FieldImmutableException.class);
        }
    }

    @Nested
    class SoftDeleteExpense {

        @Test
        void expenseNotFound_throwsExpenseNotFoundException() {
            UUID userId = UUID.randomUUID();
            UUID expenseId = UUID.randomUUID();
            LocalDate expenseDate = LocalDate.of(2026, 1, 11);

            when(expenseRepository.findActiveByIdAndDate(userId, expenseId, expenseDate))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.softDeleteExpense(userId, expenseId, expenseDate))
                    .isInstanceOf(ExpenseNotFoundException.class);
        }

        // Bug #6 — Clock injection: deletedAt must come from the injected Clock, not Instant.now().
        @Test
        void setsDeletedAt_toClockInstant() {
            UUID userId = UUID.randomUUID();
            UUID expenseId = UUID.randomUUID();
            LocalDate expenseDate = LocalDate.of(2026, 1, 11);
            Instant fixedInstant = Instant.parse("2026-01-11T10:00:00Z");

            when(clock.instant()).thenReturn(fixedInstant);
            when(clock.getZone()).thenReturn(ZoneOffset.UTC);

            ExpenseEntity expense = new ExpenseEntity();
            expense.setSource(ExpenseSource.MANUAL.name());
            when(expenseRepository.findActiveByIdAndDate(userId, expenseId, expenseDate))
                    .thenReturn(Optional.of(expense));
            ArgumentCaptor<ExpenseEntity> captor = ArgumentCaptor.forClass(ExpenseEntity.class);
            when(expenseRepository.save(captor.capture())).thenReturn(expense);

            service.softDeleteExpense(userId, expenseId, expenseDate);

            assertThat(captor.getValue().getDeletedAt()).isEqualTo(fixedInstant);
        }
    }

    // Bug #4 — count-query SQL building: the filter helper must produce SQL that
    // the count and data queries can use directly, with no string transformation.
    @Nested
    class CountQueryBuilding {

        @Test
        void noFilters_filterStartsWithJoinAndWhereClause() {
            UUID userId = UUID.randomUUID();
            ExpenseQuery q = new ExpenseQuery(
                    null, null, null, null, null, null, null, null, null,
                    false, 1, 20, "expense_date", "desc");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("userId", userId);

            String filter = PostgresExpenseService.buildExpenseFilter(userId, q, params);

            assertThat(filter).startsWith("LEFT JOIN expense_categories");
            assertThat(filter).contains("WHERE e.user_id = :userId");
            assertThat(filter).doesNotContain("SELECT");
            assertThat(filter).doesNotContain("ORDER BY");
            assertThat(filter).doesNotContain("LIMIT");
        }

        @Test
        void withDateFromFilter_filterContainsDateFromCondition() {
            UUID userId = UUID.randomUUID();
            LocalDate from = LocalDate.of(2026, 1, 1);
            ExpenseQuery q = new ExpenseQuery(
                    from, null, null, null, null, null, null, null, null,
                    false, 1, 20, "expense_date", "desc");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("userId", userId);

            String filter = PostgresExpenseService.buildExpenseFilter(userId, q, params);

            assertThat(filter).contains("e.expense_date >= :dateFrom");
            assertThat(params).containsEntry("dateFrom", from);
        }

        @Test
        void withCategoriesFilter_filterContainsAnyClause() {
            UUID userId = UUID.randomUUID();
            ExpenseQuery q = new ExpenseQuery(
                    null, null, null, List.of("Groceries", "Transport"), null, null,
                    null, null, null, false, 1, 20, "expense_date", "desc");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("userId", userId);

            String filter = PostgresExpenseService.buildExpenseFilter(userId, q, params);

            assertThat(filter).contains("c.name = ANY(:cats)");
            assertThat(params).containsKey("cats");
        }

        @Test
        void includeDeleted_filterOmitsDeletedAtCondition() {
            UUID userId = UUID.randomUUID();
            ExpenseQuery q = new ExpenseQuery(
                    null, null, null, null, null, null, null, null, null,
                    true, 1, 20, "expense_date", "desc");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("userId", userId);

            String filter = PostgresExpenseService.buildExpenseFilter(userId, q, params);

            assertThat(filter).doesNotContain("deleted_at");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static CategoryEntity categoryNamed(String name) {
        CategoryEntity e = new CategoryEntity();
        e.setName(name);
        return e;
    }

}
