
package com.buxby.buxby_backend.transit;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "bus_congestion_snapshots",
        indexes = {
                @Index(
                        name = "idx_bus_snapshot_route_time",
                        columnList = "bus_route_id, collected_at"
                ),
                @Index(
                        name = "idx_bus_snapshot_vehicle_time",
                        columnList = "vehicle_id, collected_at"
                )
        }
)
public class BusCongestionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    // 버스 노선 ID
    @Column(name = "bus_route_id", nullable = false, length = 50)
    public String busRouteId;

    // 차량 ID
    @Column(name = "vehicle_id", nullable = false, length = 50)
    public String vehicleId;

    // 차량 번호
    @Column(name = "vehicle_number", length = 30)
    public String vehicleNumber;

    // 다음 정류장 ID
    @Column(name = "next_stop_id", length = 50)
    public String nextStopId;

    // 다음 정류장 도착 예정시간 (초)
    @Column(name = "next_stop_arrival_seconds")
    public Integer nextStopArrivalSeconds;

    // 혼잡도 코드 (3, 4, 5, 6 등)
    @Column(name = "congestion_code", length = 10)
    public String congestionCode;

    // 혼잡도 이름
    @Column(name = "congestion_label", length = 30)
    public String congestionLabel;

    // 만차 여부 원본값
    @Column(name = "is_full_flag", length = 10)
    public String isFullFlag;

    // 정류장 도착 여부
    @Column(name = "stop_flag", length = 10)
    public String stopFlag;

    // GPS 위도
    @Column(name = "latitude")
    public Double latitude;

    // GPS 경도
    @Column(name = "longitude")
    public Double longitude;

    // 우리 서버가 실제로 수집한 시각
    @Column(name = "collected_at", nullable = false)
    public LocalDateTime collectedAt;

    // 서울시 API에서 제공한 데이터 생성 시각
    @Column(name = "source_data_time", length = 30)
    public String sourceDataTime;

    protected BusCongestionSnapshot() {
    }
}
