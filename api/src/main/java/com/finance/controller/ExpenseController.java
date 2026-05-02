package com.finance.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.finance.command.CreateExpenseCommand;
import com.finance.command.UpdateExpenseCommand;

import com.finance.domain.Expense;
import com.finance.domain.ExpensePage;
import com.finance.domain.ExpenseSummary;
import com.finance.domain.GroupBy;
import com.finance.domain.UserPrincipal;

import com.finance.dto.CreateExpenseRequest;
import com.finance.dto.ExpenseResponse;
import com.finance.dto.UpdateExpenseRequest;

import com.finance.exception.InvalidQueryException;

import com.finance.query.ExpenseQuery;
import com.finance.query.SummaryQuery;

import com.finance.service.ExpenseService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/expenses")
public class ExpenseController {

    private final ExpenseService expenseService;

    public ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    // ── POST /api/v1/expenses ─────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<?> createExpense(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateExpenseRequest request) {

        CreateExpenseCommand command = new CreateExpenseCommand(
                request.idempotencyKey(),
                request.amount(),
                request.merchantName(),
                request.expenseDate(),
                request.categories(),
                request.categoryWeights(),
                request.notes(),
                request.paymentMethod(),
                request.bankAccountId());

        Expense expense = expenseService.createExpense(principal.userId(), command);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Map.of("data", toResponse(expense)));
    }

    // ── GET /api/v1/expenses ──────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<?> getExpenses(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) String merchantName,
            @RequestParam(required = false) List<String> categories,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) UUID bankAccountId,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "expenseDate") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortOrder) {

        ExpenseQuery query = new ExpenseQuery(
                dateFrom, dateTo, merchantName, categories,
                paymentMethod, bankAccountId, minAmount, maxAmount,
                source, includeDeleted, page, pageSize, sortBy, sortOrder);

        ExpensePage result = expenseService.queryExpenses(principal.userId(), query);

        return ResponseEntity.ok(Map.of(
                "data", result.data().stream().map(this::toResponse).toList(),
                "pagination", Map.of(
                        "page",       result.page(),
                        "pageSize",   result.pageSize(),
                        "totalItems", result.totalItems(),
                        "totalPages", result.totalPages()
                )
        ));
    }

    // ── GET /api/v1/expenses/summary ──────────────────────────────────────────
    // Must be declared before /{expenseId} — Spring matches routes top to bottom.
    // If /{expenseId} comes first, "summary" would be treated as an expenseId.
    @GetMapping("/summary")
    public ResponseEntity<?> getSummary(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam LocalDate dateFrom,
            @RequestParam LocalDate dateTo,
            @RequestParam GroupBy groupBy) {

        if (dateFrom.isAfter(dateTo)) {
            throw new InvalidQueryException("dateFrom must be before dateTo");
        }

        SummaryQuery query = new SummaryQuery(dateFrom, dateTo, groupBy);

        ExpenseSummary summary = expenseService.getSummary(principal.userId(), query);

        return ResponseEntity.ok(Map.of(
                "data", Map.of(
                        "totalAmount",      summary.totalAmount(),
                        "periodFrom",       summary.periodFrom(),
                        "periodTo",         summary.periodTo(),
                        "groups",           summary.groups()
                )
        ));
    }

    // ── GET /api/v1/expenses/{expenseId} ──────────────────────────────────────
    @GetMapping("/{expenseId}")
    public ResponseEntity<?> getExpense(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID expenseId,
            @RequestParam LocalDate expenseDate) {

        Expense expense = expenseService.getExpense(
                principal.userId(), expenseId, expenseDate);

        return ResponseEntity.ok(Map.of("data", toResponse(expense)));
    }

    // ── PATCH /api/v1/expenses/{expenseId} ────────────────────────────────────
    @PatchMapping("/{expenseId}")
    public ResponseEntity<?> updateExpense(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID expenseId,
            @RequestParam LocalDate expenseDate,
            @RequestBody UpdateExpenseRequest request) {

        UpdateExpenseCommand command = new UpdateExpenseCommand(
                request.amount(),
                request.merchantName(),
                request.expenseDate(),
                request.categories(),
                request.categoryWeights(),
                request.notes(),
                request.paymentMethod());

        Expense expense = expenseService.updateExpense(
                principal.userId(), expenseId, expenseDate, command);

        return ResponseEntity.ok(Map.of("data", toResponse(expense)));
    }

    // ── DELETE /api/v1/expenses/{expenseId} ───────────────────────────────────
    @DeleteMapping("/{expenseId}")
    public ResponseEntity<Void> deleteExpense(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID expenseId,
            @RequestParam LocalDate expenseDate) {

        expenseService.softDeleteExpense(
                principal.userId(), expenseId, expenseDate);

        return ResponseEntity.noContent().build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private ExpenseResponse toResponse(Expense expense) {
        return new ExpenseResponse(
                expense.expenseId(),
                expense.amount(),
                expense.merchantName(),
                expense.expenseDate(),
                expense.categories(),
                expense.categoryWeights(),
                expense.notes(),
                expense.paymentMethod(),
                expense.bankAccountId(),
                expense.source(),
                expense.aiCategorised(),
                expense.createdAt());
    }
}