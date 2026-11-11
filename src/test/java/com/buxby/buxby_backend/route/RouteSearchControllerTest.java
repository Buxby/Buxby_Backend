package com.buxby.buxby_backend.route;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "KAKAO_REST_API_KEY=test", "KAKAO_CLIENT_SECRET=test",
        "GOOGLE_CLIENT_ID=test", "GOOGLE_CLIENT_SECRET=test",
        "NAVER_CLIENT_ID=test", "NAVER_CLIENT_SECRET=test",
        "TAGO_API_KEY=test"
})
@AutoConfigureMockMvc
class RouteSearchControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OdsayRouteService odsay;

    @Test
    void returnsOdsayResponseBodyAsIs() throws Exception {
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(new ObjectMapper().readTree("""
                {"result":{"path":[{"pathType":2,"info":{"totalTime":25},
                  "subPath":[{"trafficType":2,"startName":"A정류장","lane":[{"busNo":"6513"}]}]}]}}
                """));

        mvc.perform(get("/api/routes/search")
                        .param("startLongitude", "126.97").param("startLatitude", "37.56")
                        .param("endLongitude", "127.0").param("endLatitude", "37.58"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.path[0].info.totalTime").value(25))
                .andExpect(jsonPath("$.result.path[0].subPath[0].lane[0].busNo").value("6513"))
                .andExpect(jsonPath("$.nodeType").doesNotExist());
    }
}
