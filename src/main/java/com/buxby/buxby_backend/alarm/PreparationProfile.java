package com.buxby.buxby_backend.alarm;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "preparation_profiles")
public class PreparationProfile {

    @Id
    @Column(nullable = false)
    public String userId;

    @Column(nullable = false)
    public int initialMinutes;

    @Column(nullable = false)
    public LocalDateTime createdAt;

    protected PreparationProfile() {}

    public PreparationProfile(String userId, int initialMinutes) {
        this.userId = userId;
        this.initialMinutes = initialMinutes;
        this.createdAt = LocalDateTime.now();
    }
}
