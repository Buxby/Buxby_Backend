
package com.buxby.buxby_backend.transit;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BusCongestionSnapshotService {

    private final SeoulBusPositionService positionService;
    private final BusCongestionSnapshotRepository repository;

    public BusCongestionSnapshotService(
            SeoulBusPositionService positionService,
            BusCongestionSnapshotRepository repository
    ) {
        this.positionService = positionService;
        this.repository = repository;
    }

    // =====================================
    // 1. 실시간 혼잡도 조회 및 DB 저장
    // =====================================

    public Map<String, Object> collect(String busRouteId) {

        // 기존 실시간 API 호출
        Map<String, Object> apiResponse =
                positionService.getPositions(busRouteId);

        Object vehicleObject = apiResponse.get("vehicles");

        if (!(vehicleObject instanceof List<?> vehicles)) {
            throw new IllegalStateException(
                    "버스 차량 목록을 가져오지 못했습니다."
            );
        }

        // 한국 시간 기준 수집 시각
        LocalDateTime collectedAt =
                LocalDateTime.now(ZoneId.of("Asia/Seoul"));

        List<BusCongestionSnapshot> snapshots =
                new ArrayList<>();

        int skippedCount = 0;

        // =====================================
        // 2. 차량별 데이터를 Entity로 변환
        // =====================================

        for (Object object : vehicles) {

            if (!(object instanceof Map<?, ?> vehicle)) {
                skippedCount++;
                continue;
            }

            String vehicleId = getText(vehicle, "vehicleId");

            // 차량 ID가 없으면 기록하지 않음
            if (vehicleId == null) {
                skippedCount++;
                continue;
            }

            BusCongestionSnapshot snapshot =
                    new BusCongestionSnapshot();

            snapshot.busRouteId = busRouteId;
            snapshot.vehicleId = vehicleId;

            snapshot.vehicleNumber =
                    getText(vehicle, "vehicleNumber");

            snapshot.nextStopId =
                    getText(vehicle, "nextStopId");

            snapshot.nextStopArrivalSeconds =
                    getInteger(vehicle, "nextStopArrivalSeconds");

            snapshot.congestionCode =
                    getText(vehicle, "congestionCode");

            snapshot.congestionLabel =
                    getText(vehicle, "congestion");

            snapshot.isFullFlag =
                    getText(vehicle, "isFullFlag");

            snapshot.stopFlag =
                    getText(vehicle, "stopFlag");

            snapshot.latitude =
                    getDouble(vehicle, "latitude");

            snapshot.longitude =
                    getDouble(vehicle, "longitude");

            snapshot.sourceDataTime =
                    getText(vehicle, "dataTime");

            snapshot.collectedAt = collectedAt;

            snapshots.add(snapshot);
        }

        // =====================================
        // 3. DB에 일괄 저장
        // =====================================

        List<BusCongestionSnapshot> savedSnapshots =
                repository.saveAll(snapshots);

        // =====================================
        // 4. 저장 결과 반환
        // =====================================

        Map<String, Object> result =
                new LinkedHashMap<>();

        result.put(
                "status",
                apiResponse.getOrDefault("status", "SUCCESS")
        );
        result.put("busRouteId", busRouteId);
        result.put("fetchedCount", vehicles.size());
        result.put("savedCount", savedSnapshots.size());
        result.put("skippedCount", skippedCount);
        result.put("collectedAt", collectedAt);
        result.put("snapshots", savedSnapshots);

        return result;
    }


    // =====================================
    // 5. 과거 혼잡도 기록 조회
    // =====================================

    public List<BusCongestionSnapshot> getHistory(
            String busRouteId,
            String vehicleId
    ) {

        if (busRouteId == null || busRouteId.isBlank()) {
            throw new IllegalArgumentException(
                    "버스 노선 ID를 입력해 주세요."
            );
        }

        if (vehicleId == null || vehicleId.isBlank()) {

            return repository
                    .findTop100ByBusRouteIdOrderByCollectedAtDesc(
                            busRouteId
                    );
        }

        return repository
                .findTop100ByBusRouteIdAndVehicleIdOrderByCollectedAtDesc(
                        busRouteId,
                        vehicleId
                );
    }


    // =====================================
    // 공통 데이터 변환 함수
    // =====================================

    private String getText(Map<?, ?> map, String key) {

        Object value = map.get(key);

        if (value == null) {
            return null;
        }

        String text = value.toString().trim();

        return text.isEmpty() ? null : text;
    }


    private Integer getInteger(Map<?, ?> map, String key) {

        String value = getText(map, key);

        if (value == null) {
            return null;
        }

        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }


    private Double getDouble(Map<?, ?> map, String key) {

        String value = getText(map, key);

        if (value == null) {
            return null;
        }

        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

// =====================================
// 여러 노선의 혼잡도 조회 및 DB 저장
// =====================================


    // =====================================
    // 여러 노선의 혼잡도 조회 및 DB 저장
    // =====================================

    public Map<String, Object> collectMany(
            List<String> busRouteIds
    ) {

        // 입력값 확인
        if (busRouteIds == null || busRouteIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "버스 노선 ID를 입력해 주세요."
            );
        }

        // 빈 값 제거 및 중복 노선 제거
        Set<String> targets = new LinkedHashSet<>();

        for (String id : busRouteIds) {

            if (id == null || id.isBlank()) {
                continue;
            }

            targets.add(id.trim());
        }

        if (targets.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "유효한 버스 노선 ID가 없습니다."
            );
        }

        // 한 번의 요청에서 과도한 API 호출 방지
        if (targets.size() > 10) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "한 번에 최대 10개 노선까지 수집할 수 있습니다."
            );
        }

        List<Map<String, Object>> results =
                new ArrayList<>();

        int successCount = 0;
        int noDataCount = 0;
        int failedCount = 0;

        // 노선별 혼잡도 수집
        for (String busRouteId : targets) {

            Map<String, Object> routeResult =
                    new LinkedHashMap<>();

            routeResult.put("busRouteId", busRouteId);

            try {

                // 기존 단일 노선 수집 기능 재사용
                Map<String, Object> response =
                        collect(busRouteId);

                String collectionStatus =
                        String.valueOf(
                                response.getOrDefault("status", "SUCCESS")
                        );

                routeResult.put("status", collectionStatus);

                routeResult.put(
                        "fetchedCount",
                        response.get("fetchedCount")
                );

                routeResult.put(
                        "savedCount",
                        response.get("savedCount")
                );

                routeResult.put(
                        "collectedAt",
                        response.get("collectedAt")
                );

                // =====================================
                // 신규: 저장된 차량별 혼잡도 정보 반환
                // =====================================

                Object snapshotObject =
                        response.get("snapshots");

                List<Map<String, Object>> vehicles =
                        new ArrayList<>();

                if (snapshotObject instanceof List<?> snapshots) {

                    for (Object object : snapshots) {

                        if (!(object instanceof BusCongestionSnapshot snapshot)) {
                            continue;
                        }

                        Map<String, Object> vehicle =
                                new LinkedHashMap<>();

                        vehicle.put("id", snapshot.id);

                        vehicle.put(
                                "vehicleId",
                                snapshot.vehicleId
                        );

                        vehicle.put(
                                "vehicleNumber",
                                snapshot.vehicleNumber
                        );

                        vehicle.put(
                                "congestionCode",
                                snapshot.congestionCode
                        );

                        vehicle.put(
                                "congestionLabel",
                                snapshot.congestionLabel
                        );

                        vehicle.put(
                                "isFullFlag",
                                snapshot.isFullFlag
                        );

                        vehicle.put(
                                "nextStopId",
                                snapshot.nextStopId
                        );

                        vehicle.put(
                                "nextStopArrivalSeconds",
                                snapshot.nextStopArrivalSeconds
                        );

                        vehicle.put(
                                "latitude",
                                snapshot.latitude
                        );

                        vehicle.put(
                                "longitude",
                                snapshot.longitude
                        );

                        vehicle.put(
                                "collectedAt",
                                snapshot.collectedAt
                        );

                        vehicles.add(vehicle);
                    }
                }

                routeResult.put("vehicles", vehicles);

                if ("NO_DATA".equals(collectionStatus)) {
                    noDataCount++;
                } else {
                    successCount++;
                }

            } catch (ResponseStatusException e) {

            routeResult.put("status", "FAILED");
            routeResult.put(
                    "httpStatus",
                    e.getStatusCode().value()
            );

            routeResult.put(
                    "message",
                    e.getReason() != null
                            ? e.getReason()
                            : "외부 API 요청 실패"
            );

            failedCount++;

        } catch (Exception e) {

            routeResult.put("status", "FAILED");

            routeResult.put(
                    "errorType",
                    e.getClass().getSimpleName()
            );

            routeResult.put(
                    "message",
                    "DB 저장 또는 서버 내부 처리 중 오류가 발생했습니다."
            );

            failedCount++;
        }

            results.add(routeResult);
        }

        // =====================================
        // 전체 결과 반환
        // =====================================

        Map<String, Object> result =
                new LinkedHashMap<>();

        String status;

        if (failedCount == 0) {
            status = "SUCCESS";
        } else if (successCount > 0) {
            status = "PARTIAL_SUCCESS";
        } else {
            status = "FAILED";
        }

        result.put("status", status);
        result.put("requestedCount", targets.size());
        result.put("successCount", successCount);
        result.put("failedCount", failedCount);
        result.put("results", results);
        result.put("noDataCount", noDataCount);

        return result;
    }


}
