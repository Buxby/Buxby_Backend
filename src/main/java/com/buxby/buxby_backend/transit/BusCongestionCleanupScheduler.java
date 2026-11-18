package com.buxby.buxby_backend.transit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 오래된 버스 혼잡도 기록 정리.
 * 경로를 고를 때마다 차량별 기록이 쌓이는데 지우는 곳이 없어서,
 * Render 무료 Postgres(1GB)가 차지 않도록 보관 기간이 지난 기록을 매일 삭제한다.
 */
@Component
public class BusCongestionCleanupScheduler {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final BusCongestionSnapshotRepository repository;
    private final int retentionDays;

    public BusCongestionCleanupScheduler(
            BusCongestionSnapshotRepository repository,
            @Value("${bus.congestion.retention-days:7}") int retentionDays
    ) {
        this.repository = repository;
        this.retentionDays = Math.max(1, retentionDays);
    }

    // 매일 새벽 4시 (한국 시간)
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void deleteOldSnapshots() {
        // 수집 시각(collectedAt)이 한국 시간으로 저장되므로 같은 기준으로 비교한다
        LocalDateTime cutoff = LocalDateTime.now(SEOUL).minusDays(retentionDays);
        int deleted = repository.deleteCollectedBefore(cutoff);
        if (deleted > 0) {
            System.out.println("[BUXBY] 오래된 혼잡도 기록 " + deleted + "건 삭제 (" + cutoff + " 이전)");
        }
    }
}
