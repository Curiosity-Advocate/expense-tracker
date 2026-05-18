package com.finance.service.impl;

import com.finance.command.CreateTargetCommand;
import com.finance.command.TargetCategoryCommand;
import com.finance.domain.*;
import com.finance.entity.CategoryEntity;
import com.finance.entity.TargetCategoryEntity;
import com.finance.entity.TargetCategoryId;
import com.finance.entity.TargetEntity;
import com.finance.exception.InvalidTargetScopeException;
import com.finance.exception.TargetAlreadyExistsException;
import com.finance.exception.TargetNotFoundException;
import com.finance.prediction.NaiveDailyRateStrategy;
import com.finance.query.TargetQuery;
import com.finance.repository.CategoryRepository;
import com.finance.repository.TargetCategoryRepository;
import com.finance.repository.TargetRepository;
import com.finance.service.TargetService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class PostgresTargetService implements TargetService {

    private final TargetRepository targetRepository;
    private final TargetCategoryRepository targetCategoryRepository;
    private final CategoryRepository categoryRepository;
    private final NaiveDailyRateStrategy predictionStrategy;

    @PersistenceContext
    private EntityManager entityManager;

    public PostgresTargetService(TargetRepository targetRepository,
                                  TargetCategoryRepository targetCategoryRepository,
                                  CategoryRepository categoryRepository) {
        this.targetRepository = targetRepository;
        this.targetCategoryRepository = targetCategoryRepository;
        this.categoryRepository = categoryRepository;
        this.predictionStrategy = new NaiveDailyRateStrategy();
    }

    @Override
    public Target createTarget(UUID userId, CreateTargetCommand cmd) {
        validateScope(cmd);

        if (targetRepository.existsByUserIdAndPeriodYearAndPeriodMonthAndTargetTypeAndDeletedAtIsNull(
                userId, cmd.periodYear(), cmd.periodMonth(), cmd.targetType().name())) {
            throw new TargetAlreadyExistsException(String.format(
                    "A %s target already exists for %d-%02d", cmd.targetType(), cmd.periodYear(), cmd.periodMonth()));
        }

        TargetEntity entity = new TargetEntity();
        entity.setUserId(userId);
        entity.setTargetType(cmd.targetType().name());
        entity.setAmount(cmd.amount());
        entity.setPeriodYear(cmd.periodYear());
        entity.setPeriodMonth(cmd.periodMonth());
        TargetEntity saved = targetRepository.save(entity);

        List<TargetCategoryEntity> catRows = buildCategoryRows(saved.getId(), userId, cmd.categories());
        targetCategoryRepository.saveAll(catRows);

        return toDomain(saved, catRows);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Target> listTargets(UUID userId, TargetQuery query) {
        String typeStr = query.targetType() != null ? query.targetType().name() : null;
        return targetRepository.findActiveByUser(userId, query.periodYear(), query.periodMonth(), typeStr)
                .stream()
                .map(t -> {
                    List<TargetCategoryEntity> cats = targetCategoryRepository.findByTargetId(t.getId());
                    return toDomain(t, cats);
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TargetStatus getTargetStatus(UUID userId, UUID targetId) {
        TargetEntity target = targetRepository.findActiveById(targetId, userId)
                .orElseThrow(() -> new TargetNotFoundException(targetId));

        List<TargetCategoryEntity> catRows = targetCategoryRepository.findByTargetId(targetId);
        BigDecimal spent = computeSpent(userId, target, catRows);

        PredictionResult prediction = predictionStrategy.predict(
                target.getAmount(), spent, target.getPeriodYear(), target.getPeriodMonth());

        BigDecimal remaining = target.getAmount().subtract(spent).max(BigDecimal.ZERO);
        double pctUsed = target.getAmount().compareTo(BigDecimal.ZERO) == 0 ? 0.0
                : spent.divide(target.getAmount(), 4, RoundingMode.HALF_EVEN).doubleValue() * 100;

        return new TargetStatus(targetId, target.getAmount(), spent, remaining, pctUsed, prediction, Instant.now());
    }

    @Override
    public void deleteTarget(UUID userId, UUID targetId) {
        TargetEntity target = targetRepository.findActiveById(targetId, userId)
                .orElseThrow(() -> new TargetNotFoundException(targetId));
        target.setDeletedAt(Instant.now());
        targetRepository.save(target);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void validateScope(CreateTargetCommand cmd) {
        if (cmd.categories() == null || cmd.categories().isEmpty()) {
            if (cmd.targetType() != TargetType.TOTAL) {
                throw new InvalidTargetScopeException(cmd.targetType() + " target requires at least one INCLUSIVE category");
            }
            return;
        }

        long inclusiveCount = cmd.categories().stream()
                .filter(tc -> tc.participation() == ParticipationType.INCLUSIVE).count();
        long exclusiveCount = cmd.categories().size() - inclusiveCount;

        switch (cmd.targetType()) {
            case CATEGORY -> {
                if (inclusiveCount != 1 || exclusiveCount != 0)
                    throw new InvalidTargetScopeException("CATEGORY target requires exactly one INCLUSIVE category");
            }
            case MULTI_CATEGORY -> {
                if (inclusiveCount < 2 || exclusiveCount != 0)
                    throw new InvalidTargetScopeException("MULTI_CATEGORY target requires two or more INCLUSIVE categories");
            }
            case TOTAL -> {
                if (inclusiveCount != 0)
                    throw new InvalidTargetScopeException("TOTAL target cannot have INCLUSIVE categories; use EXCLUSIVE to carve out");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private BigDecimal computeSpent(UUID userId, TargetEntity target, List<TargetCategoryEntity> catRows) {
        int year = target.getPeriodYear();
        int month = target.getPeriodMonth();

        if (target.getTargetType().equals(TargetType.TOTAL.name())) {
            // Total spending in period
            String totalSql = "SELECT COALESCE(SUM(e.amount), 0) FROM expenses e " +
                    "WHERE e.user_id = :uid AND e.deleted_at IS NULL " +
                    "AND EXTRACT(YEAR FROM e.expense_date) = :yr " +
                    "AND EXTRACT(MONTH FROM e.expense_date) = :mo";
            BigDecimal total = (BigDecimal) entityManager.createNativeQuery(totalSql)
                    .setParameter("uid", userId).setParameter("yr", year).setParameter("mo", month)
                    .getSingleResult();

            // Subtract EXCLUSIVE categories
            List<UUID> exclusiveIds = catRows.stream()
                    .filter(c -> c.getParticipationType().equals(ParticipationType.EXCLUSIVE.name()))
                    .map(c -> c.getId().getCategoryId()).toList();

            if (!exclusiveIds.isEmpty()) {
                BigDecimal excluded = sumByCategories(userId, year, month, exclusiveIds);
                total = total.subtract(excluded).max(BigDecimal.ZERO);
            }
            return total;

        } else {
            // Sum INCLUSIVE category spending
            List<UUID> inclusiveIds = catRows.stream()
                    .filter(c -> c.getParticipationType().equals(ParticipationType.INCLUSIVE.name()))
                    .map(c -> c.getId().getCategoryId()).toList();

            if (inclusiveIds.isEmpty()) return BigDecimal.ZERO;
            return sumByCategories(userId, year, month, inclusiveIds);
        }
    }

    @SuppressWarnings("unchecked")
    private BigDecimal sumByCategories(UUID userId, int year, int month, List<UUID> categoryIds) {
        if (categoryIds.isEmpty()) return BigDecimal.ZERO;

        // Build parameterised IN clause — category IDs come from the application, not user input.
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(ec.weight_amount), 0) " +
                "FROM expense_categories ec JOIN expenses e " +
                "ON e.id = ec.expense_id AND e.expense_date = ec.expense_date " +
                "WHERE ec.user_id = :uid AND e.deleted_at IS NULL " +
                "AND EXTRACT(YEAR FROM e.expense_date) = :yr " +
                "AND EXTRACT(MONTH FROM e.expense_date) = :mo " +
                "AND ec.category_id IN (");
        for (int i = 0; i < categoryIds.size(); i++) {
            sql.append(":cid").append(i);
            if (i < categoryIds.size() - 1) sql.append(",");
        }
        sql.append(")");

        var q = entityManager.createNativeQuery(sql.toString())
                .setParameter("uid", userId)
                .setParameter("yr", year)
                .setParameter("mo", month);
        for (int i = 0; i < categoryIds.size(); i++) {
            q.setParameter("cid" + i, categoryIds.get(i));
        }
        return (BigDecimal) q.getSingleResult();
    }

    private List<TargetCategoryEntity> buildCategoryRows(UUID targetId, UUID userId,
                                                          List<TargetCategoryCommand> commands) {
        if (commands == null) return List.of();
        return commands.stream().map(tc -> new TargetCategoryEntity(
                new TargetCategoryId(targetId, tc.categoryId()),
                userId,
                tc.participation().name())).toList();
    }

    private Target toDomain(TargetEntity entity, List<TargetCategoryEntity> catRows) {
        List<TargetCategory> cats = catRows.stream().map(tc -> {
            String name = categoryRepository.findById(tc.getId().getCategoryId())
                    .map(CategoryEntity::getName).orElse("UNKNOWN");
            return new TargetCategory(tc.getId().getCategoryId(), name,
                    ParticipationType.valueOf(tc.getParticipationType()));
        }).toList();

        return new Target(
                entity.getId(),
                TargetType.valueOf(entity.getTargetType()),
                entity.getAmount(),
                entity.getPeriodYear(),
                entity.getPeriodMonth(),
                cats,
                entity.getCreatedAt());
    }
}
