package com.buxby.buxby_backend.alarm;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "alarm_reservations")
public class AlarmReservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false) public String userId;
    @Column(nullable = false) public String destination;
    @Column(nullable = false) public LocalDateTime desiredArrivalTime;
    @Column(nullable = false) public LocalDateTime wakeTime;
    @Column(nullable = false) public LocalDateTime leaveHomeTime;
    @Column(nullable = false) public int routeMinutes;
    @Column(nullable = false) public int preparationMinutes;
    @Column(nullable = false) public String status = "PENDING_CLIENT";

    // New nullable columns: old reservations remain readable.
    @Lob @Column(columnDefinition = "CLOB") public String selectedPathJson;
    public Integer bufferMinutes;
    public LocalDateTime lastAutoCheckedAt;
    public String autoCheckStatus;
    public LocalDateTime scheduleUpdatedAt;
    // V14: actual device confirmation and inferred boarding result
    public String transitOutcome; // MISSED / ASSUMED_SUCCESS
    public LocalDateTime transitOutcomeAt;
    public Integer learnedBufferMinutes;

    protected AlarmReservation() {}
    public AlarmReservation(String userId, String destination, LocalDateTime arrival,
                            LocalDateTime wake, LocalDateTime leave,
                            int routeMinutes, int preparationMinutes) {
        this.userId = userId;
        this.destination = destination;
        this.desiredArrivalTime = arrival;
        this.wakeTime = wake;
        this.leaveHomeTime = leave;
        this.routeMinutes = routeMinutes;
        this.preparationMinutes = preparationMinutes;
    }
}
