package com.finance.controller;

import com.finance.command.CreateExpenseCommand;
import com.finance.command.UpdateExpenseCommand;
import com.finance.domain.Expense;
import com.finance.domain.ExpensePage;
import com.finance.domain.ExpenseSummary;
import com.finance.domain.GroupBy;
import com.finance.domain.UserPrincipal;
import com.finance.dto.*;
import com.finance.query.ExpenseQuery;
import com.finance.query.SummaryQuery;
import com.finance.service.ExpenseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/expenses")
@Tag(name = "Expenses", description = "All date parameters and date-typed fields (expenseDate, dateFrom, dateTo) must be sent as UTC dates in YYYY-MM-DD format.")
@SecurityRequirement(name = "bearerAuth")
public class ExpenseController {

    private final ExpenseService expenseService;

    public ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    @PostMapping
    @Operation(summary = "Create a new expense")
    public ResponseEntity<ExpenseResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateExpenseRequest req) {
        Expense expense = expenseService.createExpense(principal.userId(),
                new CreateExpenseCommand(
                        UUID.fromString(req.idempotencyKey()),
                        req.amount(),
                        req.merchantName(),
                        req.expenseDate(),
                        req.categories(),
                        req.categoryWeights(),
                        req.notes(),
                        req.paymentMethod(),
                        req.bankAccountId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(expense));
    }

    @GetMapping
    @Operation(summary = "List expenses with optional filters")
    public ResponseEntity<ExpensePageResponse> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String merchantName,
            @RequestParam(required = false) List<String> categories,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) UUID bankAccountId,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "expense_date") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {
        ExpensePage result = expenseService.queryExpenses(principal.userId(), new ExpenseQuery(
                dateFrom, dateTo, merchantName, categories, paymentMethod, bankAccountId,
                minAmount, maxAmount, source, false, page, pageSize, sortBy, sortOrder));
        List<ExpenseResponse> data = result.data().stream().map(this::toResponse).toList();
        return ResponseEntity.ok(new ExpensePageResponse(
                data, result.page(), result.pageSize(), result.totalItems(), result.totalPages()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single expense by id and date")
    public ResponseEntity<ExpenseResponse> get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expenseDate) {
        return ResponseEntity.ok(toResponse(expenseService.getExpense(principal.userId(), id, expenseDate)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Partially update an expense")
    public ResponseEntity<ExpenseResponse> update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expenseDate,
            @Valid @RequestBody UpdateExpenseRequest req) {
        Expense updated = expenseService.updateExpense(principal.userId(), id, expenseDate,
                new UpdateExpenseCommand(
                        req.amount(),
                        req.merchantName(),
                        null, // expenseDate change not supported on partitioned table
                        req.categories(),
                        req.categoryWeights(),
                        req.notes(),
                        req.paymentMethod()));
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete an expense")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expenseDate) {
        expenseService.softDeleteExpense(principal.userId(), id, expenseDate);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/summary")
    @Operation(summary = "Aggregate expense summary grouped by category, merchant, or month")
    public ResponseEntity<ExpenseSummaryResponse> summary(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "MONTH") GroupBy groupBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        ExpenseSummary summary = expenseService.getSummary(principal.userId(),
                new SummaryQuery(dateFrom, dateTo, groupBy));
        List<SummaryGroupResponse> groups = summary.groups().stream()
                .map(g -> new SummaryGroupResponse(g.groupKey(), g.totalAmount(),
                        g.transactionCount(), g.percentageOfTotal()))
                .toList();
        return ResponseEntity.ok(new ExpenseSummaryResponse(
                groupBy.name(), summary.periodFrom(), summary.periodTo(), summary.totalAmount(), groups));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ExpenseResponse toResponse(Expense e) {
        List<CategoryAllocation> cats = e.categories().stream()
                .map(name -> new CategoryAllocation(null, name,
                        e.categoryWeights() != null ? e.categoryWeights().get(name) : null))
                .toList();
        return new ExpenseResponse(e.expenseId(), e.expenseDate(), e.amount(), e.merchantName(),
                e.paymentMethod(), e.bankAccountId(), e.source().name(), e.aiCategorised(),
                cats, e.notes(), e.createdAt());
    }
}
