package com.buxby.buxby_backend;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 외부 API(ODsay, TAGO, 서울시, 카카오, Gemini) 호출용 RestClient.
 * RestClient.create()는 타임아웃이 없어서 외부 서버가 응답하지 않으면
 * 요청 스레드와 스케줄러 스레드가 무한정 멈춘다.
 */
public final class HttpClients {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 교통 API처럼 금방 응답해야 하는 호출 */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    /** AI 호출. 혼잡해 늦으면 GeminiClient가 대체 모델로 넘어가므로 한 번에 너무 오래 기다리지 않는다 */
    public static final Duration AI_READ_TIMEOUT = Duration.ofSeconds(25);

    private HttpClients() {
    }

    public static RestClient create() {
        return create(DEFAULT_READ_TIMEOUT);
    }

    public static RestClient create(Duration readTimeout) {
        // RestClient.create()의 기본값과 같은 JDK HttpClient를 쓰고 타임아웃만 추가한다
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }
}
