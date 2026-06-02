package com.finance.controller;

import com.finance.command.CreateTargetCommand;
import com.finance.command.TargetCategoryCommand;
import com.finance.domain.*;
import com.finance.dto.*;
import com.finance.query.TargetQuery;
import com.finance.service.TargetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/targets")
@Tag(name = "Targets")
@SecurityRequirement(name = "bearerAuth")
public class TargetController {

    private final TargetService targetService;

    public TargetController(TargetService targetService) {
        this.targetService = targetService;
    }

    @PostMapping
    @Operation(summary = "Create a spending target for a period")
    public ResponseEntity<TargetResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateTargetRequest req) {
        List<TargetCategoryCommand> catCommands = req.categories() == null ? List.of()
                : req.categories().stream()
                        .map(r -> new TargetCategoryCommand(r.categoryId(), r.participation()))
                        .toList();
        Target target = targetService.createTarget(principal.userId(),
                new CreateTargetCommand(req.targetType(), req.amount(),
                        req.periodYear(), req.periodMonth(), catCommands));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(target));
    }

    @GetMapping
    @Operation(summary = "List active targets with optional filters")
    public ResponseEntity<List<TargetResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Integer periodYear,
            @RequestParam(required = false) Integer periodMonth,
            @RequestParam(required = false) TargetType targetType) {
        List<TargetResponse> targets = targetService.listTargets(principal.userId(),
                new TargetQuery(periodYear, periodMonth, targetType))
                .stream().map(this::toResponse).toList();
        return ResponseEntity.ok(targets);
    }

    @GetMapping("/{id}/status")
    @Operation(summary = "Get live status and end-of-month prediction for a target")
    public ResponseEntity<TargetStatusResponse> status(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        TargetStatus status = targetService.getTargetStatus(principal.userId(), id);
        PredictionResultResponse pred = status.prediction() == null ? null
                : new PredictionResultResponse(
                        status.prediction().projectedAmount(),
                        status.prediction().willExceedTarget(),
                        status.prediction().projectedExceedanceAmount(),
                        status.prediction().strategyUsed(),
                        status.prediction().strategyVersion(),
                        status.prediction().confidence().name(),
                        status.prediction().daysElapsed(),
                        status.prediction().daysRemainingInPeriod());
        return ResponseEntity.ok(new TargetStatusResponse(
                status.targetId(), status.targetAmount(), status.spentAmount(),
                status.remainingAmount(), status.percentageUsed(), pred, status.dataFreshAsOf()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete a target")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        targetService.deleteTarget(principal.userId(), id);
        return ResponseEntity.noContent().build();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private TargetResponse toResponse(Target t) {
        List<TargetCategoryResponse> cats = t.categories().stream()
                .map(c -> new TargetCategoryResponse(c.categoryId(), c.categoryName(),
                        c.participation().name()))
                .toList();
        return new TargetResponse(t.targetId(), t.targetType().name(), t.amount(),
                t.periodYear(), t.periodMonth(), cats, t.createdAt());
    }
}
