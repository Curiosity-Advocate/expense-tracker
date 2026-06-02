package com.finance.repository;

import com.finance.entity.TargetCategoryEntity;
import com.finance.entity.TargetCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TargetCategoryRepository extends JpaRepository<TargetCategoryEntity, TargetCategoryId> {

    @Query("SELECT tc FROM TargetCategoryEntity tc WHERE tc.id.targetId = :targetId")
    List<TargetCategoryEntity> findByTargetId(@Param("targetId") UUID targetId);
}
