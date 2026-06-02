package com.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class TargetCategoryId implements Serializable {

    @Column(name = "target_id")
    private UUID targetId;

    @Column(name = "category_id")
    private UUID categoryId;

    public TargetCategoryId() {}

    public TargetCategoryId(UUID targetId, UUID categoryId) {
        this.targetId = targetId;
        this.categoryId = categoryId;
    }

    public UUID getTargetId() { return targetId; }
    public UUID getCategoryId() { return categoryId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TargetCategoryId that)) return false;
        return Objects.equals(targetId, that.targetId) && Objects.equals(categoryId, that.categoryId);
    }

    @Override
    public int hashCode() { return Objects.hash(targetId, categoryId); }
}
