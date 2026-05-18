package com.finance.repository;

import com.finance.entity.CategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<CategoryEntity, UUID> {

    // System categories (user_id IS NULL) are visible to all users.
    @Query("SELECT c FROM CategoryEntity c WHERE c.userId IS NULL OR c.userId = :userId ORDER BY c.name")
    List<CategoryEntity> findAllVisibleToUser(@Param("userId") UUID userId);

    @Query("SELECT c FROM CategoryEntity c WHERE (c.userId IS NULL OR c.userId = :userId) AND c.name = :name")
    Optional<CategoryEntity> findByNameVisibleToUser(@Param("userId") UUID userId, @Param("name") String name);

    boolean existsByUserIdAndName(UUID userId, String name);

    @Query("SELECT c FROM CategoryEntity c WHERE c.userId IS NULL OR c.userId = :userId")
    List<CategoryEntity> findAllVisibleToUserByIds(@Param("userId") UUID userId);
}
