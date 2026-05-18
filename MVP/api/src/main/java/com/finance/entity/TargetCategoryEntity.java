package com.finance.entity;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "target_categories")
public class TargetCategoryEntity {

    @EmbeddedId
    private TargetCategoryId id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "participation_type", nullable = false)
    private String participationType;

    public TargetCategoryEntity() {}

    public TargetCategoryEntity(TargetCategoryId id, UUID userId, String participationType) {
        this.id = id;
        this.userId = userId;
        this.participationType = participationType;
    }

    public TargetCategoryId getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getParticipationType() { return participationType; }
}
