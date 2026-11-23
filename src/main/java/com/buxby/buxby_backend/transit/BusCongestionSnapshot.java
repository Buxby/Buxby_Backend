
package com.buxby.buxby_backend.transit;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "bus_congestion_snapshots",
        indexes = {
                @Index(
                        name = "idx_bus_snapshot_provider_route",
                        columnList = "provider, bus_city_code, bus_route_id, collected_at"
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

    // 교통정보 제공기관
    @Column(name = "provider", length = 20)
    public String provider;

    // 운행 도시 코드
    @Column(name = "bus_city_code")
    public Integer busCityCode;

    // 버스 번호
    @Column(name = "bus_no", length = 30)
    public String busNo;

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

    // 현재 정류장
    @Column(name = "current_station_id", length = 50)
    public String currentStationId;

    // 현재 정류장 순번
    @Column(name = "station_sequence", length = 20)
    public String stationSequence;

    // 현재 정류장 이름 (인천 API)
    @Column(name = "current_station_name", length = 100)
    public String currentStationName;

    // 진행 방향 코드 및 이름
    @Column(name = "direction_code", length = 10)
    public String directionCode;

    @Column(name = "direction", length = 20)
    public String direction;

    // 현재 위치 노드 순번 (인천 API)
    @Column(name = "node_sequence", length = 20)
    public String nodeSequence;

    // 막차 여부
    @Column(name = "last_bus_code", length = 10)
    public String lastBusCode;

    @Column(name = "last_bus", length = 20)
    public String lastBus;

    // 차량 운행 상태
    @Column(name = "vehicle_state_code", length = 10)
    public String vehicleStateCode;

    @Column(name = "vehicle_state", length = 30)
    public String vehicleState;

    // 잔여 좌석 수
    @Column(name = "remain_seat_count")
    public Integer remainSeatCount;

    // 경기 버스 추가 정보
    @Column(name = "route_type_code", length = 10)
    public String routeTypeCode;

    @Column(name = "low_plate", length = 10)
    public String lowPlate;

    @Column(name = "tagless_code", length = 10)
    public String taglessCode;

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
