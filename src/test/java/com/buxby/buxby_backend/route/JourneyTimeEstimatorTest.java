package com.buxby.buxby_backend.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyTimeEstimatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JourneyTimeEstimator estimator = new JourneyTimeEstimator();

    private JsonNode path(String subPaths, int totalTime) throws Exception {
        return mapper.readTree("{\"info\":{\"totalTime\":" + totalTime + "},\"subPath\":[" + subPaths + "]}");
    }

    @Test
    void 보통은_평균대기_늦을때는_90퍼센트_대기를_더한다() throws Exception {
        // 도보 1 → 지하철(간격 5) 26 → 도보 2 → 지하철(간격 6) 3 → 도보 8 (실제 ODsay 응답 형태)
        JsonNode p = path("""
                {"trafficType":3,"sectionTime":1},
                {"trafficType":1,"sectionTime":26,"intervalTime":5},
                {"trafficType":3,"sectionTime":2},
                {"trafficType":1,"sectionTime":3,"intervalTime":6},
                {"trafficType":3,"sectionTime":8}
                """, 42);

        JourneyTimeEstimator.TimeEstimate estimate = estimator.estimate(p);

        // 평균 대기 2.5 + 3 = 5.5분, 90% 지점 약 8.4분
        assertThat(estimate.minMinutes()).isEqualTo(48);
        assertThat(estimate.maxMinutes()).isEqualTo(50);
    }

    @Test
    void 모든_구간의_최대대기를_더하지_않는다() throws Exception {
        // 배차 15분 + 30분: 최대 대기를 다 더하면 45분이지만 90% 지점은 약 35분
        JsonNode p = path("""
                {"trafficType":2,"sectionTime":30,"intervalTime":15},
                {"trafficType":2,"sectionTime":20,"intervalTime":30}
                """, 57);

        JourneyTimeEstimator.TimeEstimate estimate = estimator.estimate(p);

        assertThat(estimate.minMinutes()).isEqualTo(57 + 23);
        assertThat(estimate.maxMinutes()).isEqualTo(57 + 35);
    }

    @Test
    void 배차간격이_없으면_기본값_길면_상한을_쓴다() throws Exception {
        JsonNode p = path("""
                {"trafficType":2,"sectionTime":20},
                {"trafficType":2,"sectionTime":40,"intervalTime":120}
                """, 60);

        JourneyTimeEstimator.Wait wait = JourneyTimeEstimator.waitFor(p, false);

        // 기본 10분 + 상한 30분: 평균 5 + 15 = 20분
        assertThat(wait.expected()).isEqualTo(20);
        assertThat(wait.p90()).isEqualTo(32);
    }

    @Test
    void 대중교통이_없으면_대기시간을_더하지_않는다() throws Exception {
        JsonNode p = path("{\"trafficType\":3,\"sectionTime\":15}", 15);

        JourneyTimeEstimator.TimeEstimate estimate = estimator.estimate(p);

        assertThat(estimate.minMinutes()).isEqualTo(15);
        assertThat(estimate.maxMinutes()).isEqualTo(15);
    }

    @Test
    void 첫_탑승_대기를_빼면_환승_대기만_남는다() throws Exception {
        JsonNode p = path("""
                {"trafficType":2,"sectionTime":10,"intervalTime":8},
                {"trafficType":1,"sectionTime":20,"intervalTime":4}
                """, 30);

        assertThat(JourneyTimeEstimator.waitFor(p, true)).isEqualTo(new JourneyTimeEstimator.Wait(2, 3));
        assertThat(JourneyTimeEstimator.waitFor(p, false)).isEqualTo(new JourneyTimeEstimator.Wait(6, 9));
    }
}
