package com.buxby.buxby_backend.alarm;

import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity
@Table(name="preparation_records")
public class PreparationRecord {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false) public String userId;
    @Column(nullable=false) public int minutes;
    @Column(nullable=false) public LocalDateTime recordedAt = LocalDateTime.now();
    protected PreparationRecord() {}
    public PreparationRecord(String userId, int minutes) { this.userId=userId;this.minutes=minutes; }
}
