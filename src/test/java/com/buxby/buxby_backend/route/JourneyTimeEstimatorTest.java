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
    void 최대_소요시간에_구간별_배차간격을_더한다() throws Exception {
        // 도보 1 → 지하철(간격 5) 26 → 도보 2 → 지하철(간격 6) 3 → 도보 8 (실제 ODsay 응답 형태)
        JsonNode p = path("""
                {"trafficType":3,"sectionTime":1},
                {"trafficType":1,"sectionTime":26,"intervalTime":5},
                {"trafficType":3,"sectionTime":2},
                {"trafficType":1,"sectionTime":3,"intervalTime":6},
                {"trafficType":3,"sectionTime":8}
                """, 42);

        JourneyTimeEstimator.TimeEstimate estimate = estimator.estimate(p);

        assertThat(estimate.minMinutes()).isEqualTo(42);
        assertThat(estimate.maxMinutes()).isEqualTo(42 + 5 + 6);
    }

    @Test
    void 배차간격이_없으면_기본값_길면_상한을_쓴다() throws Exception {
        JsonNode p = path("""
                {"trafficType":2,"sectionTime":20},
                {"trafficType":2,"sectionTime":40,"intervalTime":120}
                """, 60);

        JourneyTimeEstimator.TimeEstimate estimate = estimator.estimate(p);

        assertThat(estimate.maxMinutes()).isEqualTo(60
                + JourneyTimeEstimator.DEFAULT_WAIT_PER_LEG + JourneyTimeEstimator.MAX_WAIT_PER_LEG);
    }

    @Test
    void 대중교통이_없으면_대기시간을_더하지_않는다() throws Exception {
        JsonNode p = path("{\"trafficType\":3,\"sectionTime\":15}", 15);

        assertThat(estimator.estimate(p).maxMinutes()).isEqualTo(15);
    }

    @Test
    void 첫_탑승_대기를_빼면_환승_대기만_남는다() throws Exception {
        JsonNode p = path("""
                {"trafficType":2,"sectionTime":10,"intervalTime":8},
                {"trafficType":1,"sectionTime":20,"intervalTime":4}
                """, 30);

        assertThat(JourneyTimeEstimator.waitMargin(p, true)).isEqualTo(4);
        assertThat(JourneyTimeEstimator.waitMargin(p, false)).isEqualTo(12);
    }
}
