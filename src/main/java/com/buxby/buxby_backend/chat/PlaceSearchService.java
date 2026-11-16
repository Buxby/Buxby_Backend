
package com.buxby.buxby_backend.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
public class PlaceSearchService {

    public record Place(
            String name,
            String address,
            double latitude,
            double longitude
    ) {}

    private final RestClient client = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String key;

    public PlaceSearchService(
            @Value("${kakao.rest-api-key:}") String key
    ) {
        this.key = key;
    }

    public List<Place> search(String query) {

        if (query == null || query.isBlank() || query.length() > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "검색할 장소명을 확인해 주세요."
            );
        }

        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "카카오 REST API 키가 없습니다."
            );
        }

        try {
            String response = client.get()
                    .uri(builder -> builder
                            .scheme("https")
                            .host("dapi.kakao.com")
                            .path("/v2/local/search/keyword.json")
                            .queryParam("query", query)
                            .queryParam("size", 5)
                            .build())
                    .header("Authorization", "KakaoAK " + key)
                    .retrieve()
                    .body(String.class);

            if (response == null || response.isBlank()) {
                throw new IllegalStateException(
                        "카카오 API 응답이 비어 있습니다."
                );
            }

            JsonNode root = objectMapper.readTree(response);
            JsonNode documents = root.path("documents");

            if (!documents.isArray()) {
                throw new IllegalStateException(
                        "카카오 API 응답에 documents 배열이 없습니다."
                );
            }

            List<Place> found = new ArrayList<>();

            for (JsonNode item : documents) {

                String name = item.path("place_name").asText();

                String address = item.path("road_address_name").asText();

                if (address.isBlank()) {
                    address = item.path("address_name").asText();
                }

                double latitude = Double.parseDouble(
                        item.path("y").asText()
                );

                double longitude = Double.parseDouble(
                        item.path("x").asText()
                );

                found.add(new Place(
                        name,
                        address,
                        latitude,
                        longitude
                ));
            }

            System.out.println(
                    "[Kakao Place] 검색 성공: " + query
                            + " / 결과 " + found.size() + "개"
            );

            return found;

        } catch (RestClientResponseException e) {

            System.err.println(
                    "[Kakao Place] HTTP 오류: "
                            + e.getStatusCode()
            );

            System.err.println(
                    "[Kakao Place] 응답: "
                            + e.getResponseBodyAsString()
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "카카오 장소 검색 실패 (HTTP "
                            + e.getStatusCode().value() + ")",
                    e
            );

        } catch (ResponseStatusException e) {
            throw e;

        } catch (Exception e) {

            System.err.println(
                    "[Kakao Place] 장소 검색 처리 오류"
            );

            e.printStackTrace();

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "장소 검색 응답 처리 실패: "
                            + e.getClass().getSimpleName(),
                    e
            );
        }
    }
}
