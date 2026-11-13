
package com.buxby.buxby_backend.transit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(
        prefix = "bus.congestion.scheduler",
        name = "enabled",
        havingValue = "true"
)
public class BusCongestionScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(BusCongestionScheduler.class);

    private final BusCongestionSnapshotService snapshotService;

    private final String routeIds;

    public BusCongestionScheduler(
            BusCongestionSnapshotService snapshotService,
            @Value("${bus.congestion.scheduler.route-ids:100100521}")
            String routeIds
    ) {
        this.snapshotService = snapshotService;
        this.routeIds = routeIds;
    }

    // =====================================
    // 자동 혼잡도 수집
    // =====================================

    @Scheduled(
            initialDelayString =
                    "${bus.congestion.scheduler.initial-delay-ms:60000}",

            fixedDelayString =
                    "${bus.congestion.scheduler.fixed-delay-ms:300000}"
    )
    public void collectBusCongestion() {

        List<String> targets = Arrays.stream(routeIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .distinct()
                .toList();

        if (targets.isEmpty()) {
            log.warn("혼잡도 수집 대상 노선이 없습니다.");
            return;
        }

        log.info(
                "===== 버스 혼잡도 자동 수집 시작 (대상 {}개 노선) =====",
                targets.size()
        );

        for (String busRouteId : targets) {

            try {

                Map<String, Object> result =
                        snapshotService.collect(busRouteId);

                Object fetchedCount = result.get("fetchedCount");
                Object savedCount = result.get("savedCount");

                log.info(
                        "[혼잡도 수집 성공] 노선 ID: {}, 조회 차량: {}, 저장 기록: {}",
                        busRouteId,
                        fetchedCount,
                        savedCount
                );

            } catch (Exception e) {

                // 한 노선에서 오류가 발생해도
                // 다른 노선의 수집은 계속 진행
                log.error(
                        "[혼잡도 수집 실패] 노선 ID: {}",
                        busRouteId,
                        e
                );

            }
        }

        log.info("===== 버스 혼잡도 자동 수집 종료 =====");
    }
}
