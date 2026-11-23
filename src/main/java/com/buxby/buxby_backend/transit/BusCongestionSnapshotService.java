
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
    private final GyeonggiBusPositionService gyeonggiPositionService;
    private final BusCongestionSnapshotRepository repository;

    public BusCongestionSnapshotService(
            SeoulBusPositionService positionService,
            GyeonggiBusPositionService gyeonggiPositionService,
            BusCongestionSnapshotRepository repository
    ) {
        this.positionService = positionService;
        this.gyeonggiPositionService = gyeonggiPositionService;
        this.repository = repository;
    }

    // =====================================
    // 지역별 실시간 버스 혼잡도 조회 및 DB 저장
    // =====================================

    // 기존 Scheduler 및 단일 수집 기능 호환
    public Map<String, Object> collect(String busRouteId) {

        return collect(
                new BusPositionCollectRequest.BusTarget(
                        busRouteId,
                        null,
                        "SEOUL",
                        4,
                        1000
                )
        );
    }


    // 지역별 API 선택
    public Map<String, Object> collect(
            BusPositionCollectRequest.BusTarget target
    ) {

        if (target == null
                || target.busRouteId() == null
                || target.busRouteId().isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "버스 노선 ID를 입력해 주세요."
            );
        }

        String busRouteId = target.busRouteId().trim();

        String provider = target.provider() == null
                ? "UNKNOWN"
                : target.provider().trim().toUpperCase();

        // =====================================
        // 1. 지역별 API 호출
        // =====================================

        Map<String, Object> apiResponse;

        switch (provider) {

            case "SEOUL" ->
                    apiResponse =
                            positionService.getPositions(busRouteId);

            case "GYEONGGI" ->
                    apiResponse =
                            gyeonggiPositionService.getPositions(busRouteId);

            default ->
                    throw new ResponseStatusException(
                            HttpStatus.NOT_IMPLEMENTED,
                            "아직 지원하지 않는 버스 제공기관입니다: "
                                    + provider
                    );
        }

        Object vehicleObject = apiResponse.get("vehicles");

        if (!(vehicleObject instanceof List<?> vehicles)) {
            throw new IllegalStateException(
                    "버스 차량 목록을 가져오지 못했습니다."
            );
        }

        LocalDateTime collectedAt =
                LocalDateTime.now(ZoneId.of("Asia/Seoul"));

        List<BusCongestionSnapshot> snapshots =
                new ArrayList<>();

        int skippedCount = 0;

        // =====================================
        // 2. 차량 정보를 DB Entity로 변환
        // =====================================

        for (Object object : vehicles) {

            if (!(object instanceof Map<?, ?> vehicle)) {
                skippedCount++;
                continue;
            }

            String vehicleId = getText(vehicle, "vehicleId");

            if (vehicleId == null) {
                skippedCount++;
                continue;
            }

            BusCongestionSnapshot snapshot =
                    new BusCongestionSnapshot();

            // 지역 및 노선 정보
            snapshot.provider = provider;
            snapshot.busCityCode = target.busCityCode();
            snapshot.busNo = target.busNo();
            snapshot.busRouteId = busRouteId;

            // 차량 기본 정보
            snapshot.vehicleId = vehicleId;
            snapshot.vehicleNumber =
                    getText(vehicle, "vehicleNumber");

            // 서울시 및 경기 API 공통 정보
            snapshot.congestionCode =
                    getText(vehicle, "congestionCode");

            String congestionLabel =
                    getText(vehicle, "congestionLabel");

            if (congestionLabel == null) {
                congestionLabel =
                        getText(vehicle, "congestion");
            }

            snapshot.congestionLabel = congestionLabel;

            // 서울시 API 정보
            snapshot.nextStopId =
                    getText(vehicle, "nextStopId");

            snapshot.nextStopArrivalSeconds =
                    getInteger(vehicle, "nextStopArrivalSeconds");

            snapshot.isFullFlag =
                    getText(vehicle, "isFullFlag");

            snapshot.stopFlag =
                    getText(vehicle, "stopFlag");

            snapshot.latitude =
                    getDouble(vehicle, "latitude");

            snapshot.longitude =
                    getDouble(vehicle, "longitude");

            // 경기 API 추가 정보
            snapshot.currentStationId =
                    getText(vehicle, "currentStationId");

            snapshot.stationSequence =
                    getText(vehicle, "stationSequence");

            snapshot.vehicleStateCode =
                    getText(vehicle, "vehicleStateCode");

            snapshot.vehicleState =
                    getText(vehicle, "vehicleState");

            snapshot.remainSeatCount =
                    getInteger(vehicle, "remainSeatCount");

            snapshot.routeTypeCode =
                    getText(vehicle, "routeTypeCode");

            snapshot.lowPlate =
                    getText(vehicle, "lowPlate");

            snapshot.taglessCode =
                    getText(vehicle, "taglessCode");

            // 외부 API 데이터 수집 시각
            String sourceTime =
                    getText(vehicle, "dataTime");

            if (sourceTime == null && apiResponse.get("queryTime") != null) {
                sourceTime = apiResponse.get("queryTime").toString();
            }

            snapshot.sourceDataTime = sourceTime;
            snapshot.collectedAt = collectedAt;

            snapshots.add(snapshot);
        }

        // =====================================
        // 3. DB 일괄 저장
        // =====================================

        List<BusCongestionSnapshot> savedSnapshots =
                repository.saveAll(snapshots);

        // =====================================
        // 4. 결과 반환
        // =====================================

        Map<String, Object> result =
                new LinkedHashMap<>();

        result.put(
                "status",
                apiResponse.getOrDefault("status", "SUCCESS")
        );

        result.put("provider", provider);
        result.put("busCityCode", target.busCityCode());
        result.put("busNo", target.busNo());
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

    // 기존 요청 형식 호환
    public Map<String, Object> collectMany(
            List<String> busRouteIds
    ) {

        if (busRouteIds == null || busRouteIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "버스 노선 ID를 입력해 주세요."
            );
        }

        List<BusPositionCollectRequest.BusTarget> targets =
                new ArrayList<>();

        for (String id : busRouteIds) {

            if (id == null || id.isBlank()) continue;

            targets.add(
                    new BusPositionCollectRequest.BusTarget(
                            id.trim(),
                            null,
                            "SEOUL",
                            4,
                            1000
                    )
            );
        }

        return collectManyTargets(targets);
    }


    // 지역별 요청을 처리하는 통합 Batch
    public Map<String, Object> collectManyTargets(
            List<BusPositionCollectRequest.BusTarget> requestedTargets
    ) {

        if (requestedTargets == null || requestedTargets.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "버스 수집 대상이 없습니다."
            );
        }

        // 지역 + 도시 + 노선 ID를 기준으로 중복 제거
        Map<String, BusPositionCollectRequest.BusTarget> targets =
                new LinkedHashMap<>();

        for (BusPositionCollectRequest.BusTarget target : requestedTargets) {

            if (target == null
                    || target.busRouteId() == null
                    || target.busRouteId().isBlank()) {
                continue;
            }

            String provider = target.provider() == null
                    ? "UNKNOWN"
                    : target.provider().trim().toUpperCase();

            String routeId = target.busRouteId().trim();

            String key =
                    provider + ":" +
                            target.busCityCode() + ":" +
                            routeId;

            targets.putIfAbsent(
                    key,
                    new BusPositionCollectRequest.BusTarget(
                            routeId,
                            target.busNo(),
                            provider,
                            target.providerCode(),
                            target.busCityCode()
                    )
            );
        }

        if (targets.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "유효한 버스 수집 대상이 없습니다."
            );
        }

        if (targets.size() > 10) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "한 번에 최대 10개 노선까지 수집할 수 있습니다."
            );
        }

        List<Map<String, Object>> results = new ArrayList<>();

        int successCount = 0;
        int noDataCount = 0;
        int failedCount = 0;

        for (BusPositionCollectRequest.BusTarget target
                : targets.values()) {

            Map<String, Object> routeResult =
                    new LinkedHashMap<>();

            routeResult.put("provider", target.provider());
            routeResult.put("busNo", target.busNo());
            routeResult.put("busCityCode", target.busCityCode());
            routeResult.put("busRouteId", target.busRouteId());

            try {

                Map<String, Object> response = collect(target);

                String status = String.valueOf(
                        response.getOrDefault("status", "SUCCESS")
                );

                routeResult.put("status", status);
                routeResult.put("fetchedCount", response.get("fetchedCount"));
                routeResult.put("savedCount", response.get("savedCount"));
                routeResult.put("skippedCount", response.get("skippedCount"));
                routeResult.put("collectedAt", response.get("collectedAt"));

                Object snapshotObject = response.get("snapshots");

                List<Map<String, Object>> vehicles = new ArrayList<>();

                if (snapshotObject instanceof List<?> snapshots) {

                    for (Object object : snapshots) {

                        if (!(object instanceof BusCongestionSnapshot snapshot)) {
                            continue;
                        }

                        Map<String, Object> vehicle =
                                new LinkedHashMap<>();

                        vehicle.put("id", snapshot.id);
                        vehicle.put("provider", snapshot.provider);
                        vehicle.put("busCityCode", snapshot.busCityCode);

                        vehicle.put("vehicleId", snapshot.vehicleId);
                        vehicle.put("vehicleNumber", snapshot.vehicleNumber);

                        vehicle.put("congestionCode", snapshot.congestionCode);
                        vehicle.put("congestionLabel", snapshot.congestionLabel);

                        vehicle.put("nextStopId", snapshot.nextStopId);
                        vehicle.put(
                                "nextStopArrivalSeconds",
                                snapshot.nextStopArrivalSeconds
                        );

                        vehicle.put("currentStationId", snapshot.currentStationId);
                        vehicle.put("stationSequence", snapshot.stationSequence);
                        vehicle.put("vehicleStateCode", snapshot.vehicleStateCode);
                        vehicle.put("vehicleState", snapshot.vehicleState);

                        vehicle.put("remainSeatCount", snapshot.remainSeatCount);
                        vehicle.put("routeTypeCode", snapshot.routeTypeCode);
                        vehicle.put("lowPlate", snapshot.lowPlate);
                        vehicle.put("taglessCode", snapshot.taglessCode);

                        vehicle.put("isFullFlag", snapshot.isFullFlag);
                        vehicle.put("stopFlag", snapshot.stopFlag);

                        vehicle.put("latitude", snapshot.latitude);
                        vehicle.put("longitude", snapshot.longitude);

                        vehicle.put("collectedAt", snapshot.collectedAt);

                        vehicles.add(vehicle);
                    }
                }

                routeResult.put("vehicles", vehicles);

                if ("NO_DATA".equals(status)) {
                    noDataCount++;
                } else {
                    successCount++;
                }

            } catch (ResponseStatusException e) {

                routeResult.put("status", "FAILED");
                routeResult.put("httpStatus", e.getStatusCode().value());

                routeResult.put(
                        "message",
                        e.getReason() != null
                                ? e.getReason()
                                : "외부 API 요청 실패"
                );

                failedCount++;

            } catch (Exception e) {

                routeResult.put("status", "FAILED");
                routeResult.put("errorType", e.getClass().getSimpleName());

                routeResult.put(
                        "message",
                        "DB 저장 또는 서버 내부 처리 중 오류가 발생했습니다."
                );

                failedCount++;
            }

            results.add(routeResult);
        }

        // 전체 결과
        Map<String, Object> result =
                new LinkedHashMap<>();

        String status;

        if (failedCount == 0) {
            status = "SUCCESS";
        } else if (successCount > 0 || noDataCount > 0) {
            status = "PARTIAL_SUCCESS";
        } else {
            status = "FAILED";
        }

        result.put("status", status);
        result.put("requestedCount", targets.size());
        result.put("successCount", successCount);
        result.put("noDataCount", noDataCount);
        result.put("failedCount", failedCount);
        result.put("results", results);

        return result;
    }



}
