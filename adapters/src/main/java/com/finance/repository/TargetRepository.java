package com.finance.repository;

import com.finance.entity.TargetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TargetRepository extends JpaRepository<TargetEntity, UUID> {

    @Query("SELECT t FROM TargetEntity t WHERE t.userId = :userId AND t.deletedAt IS NULL " +
           "AND (:year IS NULL OR t.periodYear = :year) " +
           "AND (:month IS NULL OR t.periodMonth = :month) " +
           "AND (:type IS NULL OR t.targetType = :type) " +
           "ORDER BY t.periodYear DESC, t.periodMonth DESC")
    List<TargetEntity> findActiveByUser(@Param("userId") UUID userId,
                                        @Param("year") Integer year,
                                        @Param("month") Integer month,
                                        @Param("type") String type);

    @Query("SELECT t FROM TargetEntity t WHERE t.id = :id AND t.userId = :userId AND t.deletedAt IS NULL")
    Optional<TargetEntity> findActiveById(@Param("id") UUID id, @Param("userId") UUID userId);

    boolean existsByUserIdAndPeriodYearAndPeriodMonthAndTargetTypeAndDeletedAtIsNull(
            UUID userId, int periodYear, int periodMonth, String targetType);
}
