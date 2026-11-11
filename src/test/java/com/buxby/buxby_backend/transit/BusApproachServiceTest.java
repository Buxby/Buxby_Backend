package com.buxby.buxby_backend.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BusApproachServiceTest {

    private final TagoService tago = mock(TagoService.class);
    private final SeoulBusAutoMatchService seoul = mock(SeoulBusAutoMatchService.class);
    private final BusApproachService service = new BusApproachService(tago, seoul);

    private static final String NEARBY = """
            {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
              {"citycode":25,"gpslati":36.33195,"gpslong":127.43239,"nodeid":"DJB8001417","nodenm":"대전역"},
              {"citycode":34070,"gpslati":36.33254,"gpslong":127.43213,"nodeid":"GRB8001418","nodenm":"대전역"},
              {"citycode":25,"gpslati":36.33254,"gpslong":127.43213,"nodeid":"DJB8001418","nodenm":"대전역"}
            ]}}}}
            """;

    private static final String ARRIVALS = """
            {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
              {"arrprevstationcnt":12,"arrtime":890,"routeid":"DJB30300052","routeno":202},
              {"arrprevstationcnt":1,"arrtime":66,"routeid":"DJB30300051","routeno":201},
              {"arrprevstationcnt":0,"arrtime":0,"routeid":"DJB30300091","routeno":711},
              {"arrprevstationcnt":4,"arrtime":361,"routeid":"DJB30300058","routeno":314}
            ]}}}}
            """;

    @Test
    void returnsOnlyRequestedRoutesSortedByArrival() {
        stub(NEARBY, ARRIVALS);

        var result = service.find("DJB8001418", "대전역", 36.3325, 127.4321,
                List.of("DJB30300058", "DJB30300091"), List.of("202"));

        assertEquals("FOUND", result.status());
        assertEquals(25, result.cityCode());
        assertEquals("DJB8001418", result.stopId());
        // 711번(0초)은 이미 도착했거나 정보가 없어 제외, 202번은 번호로 매칭
        assertEquals(List.of(
                new BusApproachService.Arrival("314", "DJB30300058", 4, 361),
                new BusApproachService.Arrival("202", "DJB30300052", 12, 890)
        ), result.arrivals());
        verify(tago).fetch("arrival", "getSttnAcctoArvlPrearngeInfoList", 25, null, null, "DJB8001418", 1, 100);
    }

    @Test
    void matchesOdsayIdsWithoutRegionPrefix() {
        stub(NEARBY, ARRIVALS);

        // 경기도처럼 ODsay가 접두사 없이 지역 ID만 주는 경우
        var result = service.find("8001417", "다른이름", 36.3319, 127.4324, List.of("30300058"), null);

        assertEquals("DJB8001417", result.stopId());
        assertEquals("314", result.arrivals().get(0).routeNo());
    }

    @Test
    void picksNearestWhenPrefixlessIdExistsInSeveralCities() {
        stub("""
                {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
                  {"citycode":34070,"gpslati":36.30,"gpslong":127.40,"nodeid":"GRB8001418","nodenm":"대전역"},
                  {"citycode":25,"gpslati":36.33254,"gpslong":127.43213,"nodeid":"DJB8001418","nodenm":"대전역"}
                ]}}}}
                """, ARRIVALS);

        var result = service.find("8001418", "대전역", 36.3325, 127.4321, List.of("30300058"), null);

        assertEquals(25, result.cityCode());
    }

    @Test
    void comparesIdsOnlyAcrossRegionPrefix() {
        assertEquals(true, BusApproachService.sameId("GGB202000208", "202000208"));
        assertEquals(true, BusApproachService.sameId("DJB8001418", "DJB8001418"));
        assertEquals(false, BusApproachService.sameId("DJB8001418", "001418"));
        assertEquals(false, BusApproachService.sameId("DJB8001418", ""));
    }

    @Test
    void fallsBackToNearestStopWithSameNameWhenIdIsUnknown() {
        stub(NEARBY, ARRIVALS);

        var result = service.find("UNKNOWN", "대전역", 36.3319, 127.4324, List.of("DJB30300051"), null);

        assertEquals("DJB8001417", result.stopId());
        assertEquals(1, result.arrivals().get(0).remainingStops());
    }

    @Test
    void reportsUnsupportedRegionWhenTagoHasNoNearbyStop() {
        stub("""
                {"response":{"header":{"resultCode":"00"},"body":{"items":"","totalCount":0}}}
                """, ARRIVALS);

        var result = service.find("112000001", "서울역", 37.5547, 126.9707, List.of("100100118"), null);

        assertEquals("UNSUPPORTED_REGION", result.status());
        assertEquals(List.of(), result.arrivals());
    }

    @Test
    void reportsNoArrivalWhenRouteIsNotApproaching() {
        stub(NEARBY, ARRIVALS);

        var result = service.find("DJB8001418", "대전역", 36.3325, 127.4321, List.of("DJB99999999"), null);

        assertEquals("NO_ARRIVAL", result.status());
    }

    @Test
    void cachesResolvedStop() {
        stub(NEARBY, ARRIVALS);

        service.find("DJB8001418", "대전역", 36.3325, 127.4321, List.of("DJB30300058"), null);
        service.find("DJB8001418", "대전역", 36.3325, 127.4321, List.of("DJB30300058"), null);

        verify(tago, times(1)).nearbyStops(anyDouble(), anyDouble(), anyInt());
    }

    private static final String NO_NEARBY = """
            {"response":{"header":{"resultCode":"00"},"body":{"items":"","totalCount":0}}}
            """;

    @Test
    void usesSeoulArrivalsWhenTagoHasNoStop() {
        stub(NO_NEARBY, ARRIVALS);
        when(seoul.routeArrivalItems("100100118")).thenReturn(List.of(
                Map.of("stId", "101000001", "rtNm", "100", "arrmsg1", "곧 도착", "exps1", "50"),
                Map.of("stId", "101000005", "rtNm", "100", "staOrd", "20",
                        "arrmsg1", "4분12초후[2번째 전]", "exps1", "252", "sectOrd1", "18",
                        "arrmsg2", "15분3초후[9번째 전]", "exps2", "903", "sectOrd2", "11")));
        when(seoul.routeArrivalItems("100100124")).thenReturn(List.of(
                Map.of("stId", "101000005", "rtNm", "143", "arrmsg1", "운행종료", "exps1", "0",
                        "arrmsg2", "운행종료", "exps2", "0")));

        var result = service.find("101000005", "서울역버스환승센터", 37.5547, 126.9707,
                List.of("100100118", "100100124"), List.of("100", "143"));

        assertEquals("FOUND", result.status());
        assertEquals("SEOUL", result.provider());
        assertEquals(List.of(
                new BusApproachService.Arrival("100", "100100118", 2, 252),
                new BusApproachService.Arrival("100", "100100118", 9, 903)
        ), result.arrivals());
    }

    @Test
    void prefersSeoulOverSameNamedTagoStopFromAnotherCity() {
        // 홍대입구역은 인천 노선이 지나 TAGO에 ICB313000422로도 등록돼 있다
        stub("""
                {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
                  {"citycode":23,"gpslati":37.5563,"gpslong":126.9236,"nodeid":"ICB313000422","nodenm":"홍대입구역"}
                ]}}}}
                """, ARRIVALS);
        when(seoul.routeArrivalItems("100100592")).thenReturn(List.of(
                Map.of("stId", "113000422", "rtNm", "7101", "arrmsg1", "12분52초후[1번째 전]", "exps1", "772")));

        var result = service.find("113000422", "홍대입구역", 37.5563, 126.9236, List.of("100100592"), List.of("7101"));

        assertEquals("SEOUL", result.provider());
        assertEquals(new BusApproachService.Arrival("7101", "100100592", 1, 772), result.arrivals().get(0));
    }

    @Test
    void reportsNoArrivalWhenSeoulBusesAreNotRunning() {
        stub(NO_NEARBY, ARRIVALS);
        when(seoul.routeArrivalItems("100100124")).thenReturn(List.of(
                Map.of("stId", "101000005", "rtNm", "143", "arrmsg1", "운행종료", "exps1", "0")));

        var result = service.find("101000005", "서울역", 37.5547, 126.9707, List.of("100100124"), null);

        assertEquals("NO_ARRIVAL", result.status());
    }

    @Test
    void readsRemainingStopsFromSeoulMessageOrStationOrder() {
        assertEquals(2, BusApproachService.seoulArrival("100", "r", "4분12초후[2번째 전]", "252", "20", "17")
                .orElseThrow().remainingStops());
        // 문구에 정류장 수가 없으면 정류소 순번 - 버스 구간 순번
        assertEquals(3, BusApproachService.seoulArrival("100", "r", "곧 도착", "40", "20", "17")
                .orElseThrow().remainingStops());
        assertEquals(true, BusApproachService.seoulArrival("100", "r", "출발대기", "0", "20", "0").isEmpty());
    }

    @Test
    void doesNotAskTagoAgainForSeoulStop() {
        stub(NO_NEARBY, ARRIVALS);
        when(seoul.routeArrivalItems("100100118")).thenReturn(List.of(
                Map.of("stId", "101000005", "rtNm", "100", "arrmsg1", "4분12초후[2번째 전]", "exps1", "252")));

        service.find("101000005", "서울역", 37.5547, 126.9707, List.of("100100118"), null);
        service.find("101000005", "서울역", 37.5547, 126.9707, List.of("100100118"), null);

        verify(tago, times(1)).nearbyStops(anyDouble(), anyDouble(), anyInt());
    }

    private void stub(String nearby, String arrivals) {
        when(tago.nearbyStops(anyDouble(), anyDouble(), anyInt())).thenReturn(read(nearby));
        when(tago.fetch(eq("arrival"), eq("getSttnAcctoArvlPrearngeInfoList"), anyInt(), eq(null), eq(null), org.mockito.ArgumentMatchers.anyString(), eq(1), eq(100)))
                .thenReturn(read(arrivals));
    }

    private static JsonNode read(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
