
package com.buxby.buxby_backend.alarm;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "missed_bus_events")
public class MissedBusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String userId;

    public Long reservationId;

    @Column(nullable = false)
    public LocalDateTime occurredAt;

    @Column(nullable = false)
    public String reason = "UNKNOWN";

    protected MissedBusEvent() {}

    public MissedBusEvent(String userId, Long reservationId) {
        this.userId = userId;
        this.reservationId = reservationId;
        this.occurredAt = LocalDateTime.now();
    }
}
