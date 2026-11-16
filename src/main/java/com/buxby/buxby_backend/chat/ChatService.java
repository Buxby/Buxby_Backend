package com.buxby.buxby_backend.chat;

import com.buxby.buxby_backend.alarm.IntegratedJourneyController;
import com.buxby.buxby_backend.alarm.AlarmReservation;
import com.buxby.buxby_backend.alarm.AlarmReservationRepository;
import com.buxby.buxby_backend.auth.CurrentUserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatService {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final long SESSION_TTL_MS = 60L * 60L * 1000L;
    private static final int MAX_TURNS = 12; // 6 pairs of user/assistant messages

    private final ObjectMapper mapper;
    private final CurrentUserService currentUser;
    private final IntegratedJourneyController journey;
    private final AlarmReservationRepository reservations;
    private final PlaceSearchService places;
    private final HomeLocationRepository homes;
    private final com.buxby.buxby_backend.alarm.TransitOutcomeService outcomes;
    private final com.buxby.buxby_backend.alarm.TransitLearningService learning;
    private final com.buxby.buxby_backend.alarm.MissedBusRouteController reroute;
    private final RestClient restClient = RestClient.create();
    private final String apiKey;
    private final String model;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public ChatService(
            ObjectMapper mapper,
            CurrentUserService currentUser,
            IntegratedJourneyController journey,
            AlarmReservationRepository reservations,
            PlaceSearchService places,
            HomeLocationRepository homes,
            com.buxby.buxby_backend.alarm.TransitOutcomeService outcomes,
            com.buxby.buxby_backend.alarm.TransitLearningService learning,
            com.buxby.buxby_backend.alarm.MissedBusRouteController reroute,
            @Value("${gemini.api.key:}") String apiKey,
            @Value("${gemini.model:gemini-2.5-flash}") String model
    ) {
        this.mapper = mapper;
        this.currentUser = currentUser;
        this.journey = journey;
        this.reservations = reservations;
        this.places = places;
        this.homes = homes;
        this.outcomes = outcomes;
        this.learning = learning;
        this.reroute = reroute;
        this.apiKey = apiKey;
        this.model = model;
    }

    public record ChatResponse(
            String sessionId,
            String status,
            String reply,
            List<String> missingFields,
            IntegratedJourneyController.JourneyResponse journey,
            Long reservationId
    ) {}

    private record Turn(String role, String text) {}

    private static final class Session {
        final Deque<Turn> turns = new ArrayDeque<>();
        volatile long lastUsed = System.currentTimeMillis();
        Double startLongitude;
        Double startLatitude;
        Double endLongitude;
        Double endLatitude;
        String destination;
        LocalDateTime desiredArrivalTime;
        Integer bufferMinutes;
        IntegratedJourneyController.JourneyResponse preview;
        String previewKey;
        Long savedReservationId;
        String resolvedDestination;
        List<PlaceSearchService.Place> destinationCandidates = List.of();
        Long pendingMissedReservationId;
    }

    public ChatResponse chat(ChatController.ChatRequest request, Authentication authentication) {
        String userId = currentUser.requireUserId(authentication);
        if (apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GEMINI_API_KEY가 필요합니다.");
        }
        if (sessions.size() > 1000) {
            long now = System.currentTimeMillis();
            sessions.entrySet().removeIf(entry -> now - entry.getValue().lastUsed > SESSION_TTL_MS);
            if (sessions.size() > 1000) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "챗봇 세션이 많습니다. 잠시 후 다시 시도하세요.");
            }
        }

        String sessionId = validSessionId(request.sessionId())
                ? request.sessionId()
                : UUID.randomUUID().toString();
        String key = userId + ":" + sessionId;
        Session session = sessions.compute(key, (ignored, existing) -> {
            if (existing == null || System.currentTimeMillis() - existing.lastUsed > SESSION_TTL_MS) {
                return new Session();
            }
            return existing;
        });

        // Serialize conversation updates for a single session; another user can't see its state.
        synchronized (session) {
            session.lastUsed = System.currentTimeMillis();
            // A location reply must finish the pending missed-transit flow before Gemini.
            if (session.pendingMissedReservationId != null) {
                if (request.startLongitude() == null || request.startLatitude() == null) {
                    String reply = "현재 위치를 공유해 줘. 위치를 받으면 대체 경로를 찾아줄게.";
                    remember(session, request.message(), reply);
                    return new ChatResponse(sessionId, "LOCATION_REQUIRED", reply,
                            List.of("startLongitude", "startLatitude"), null, session.pendingMissedReservationId);
                }
                Long reservationId = session.pendingMissedReservationId;
                var routes = reroute.reroute(
                        new com.buxby.buxby_backend.alarm.MissedBusRouteController.RerouteRequest(
                                reservationId, request.startLongitude(), request.startLatitude()), authentication);
                String reply = formatMissedRoutes(routes);
                if ("ROUTES_FOUND".equals(routes.status())) session.pendingMissedReservationId = null;
                remember(session, request.message(), reply);
                return new ChatResponse(sessionId, routes.status().equals("ROUTES_FOUND") ? "REROUTED" : "NO_ROUTE",
                        reply, List.of(), null, reservationId);
            }
            // V14: recognize missed transit directly, without an LLM call.
            if (isMissedTransit(request.message())) {
                // Only select reservations that can actually be rerouted.
                // Old reservations may not contain selectedPathJson.
                LocalDateTime now = LocalDateTime.now(KOREA);
                AlarmReservation active = reservations.findByUserIdOrderByDesiredArrivalTimeDesc(userId).stream()
                        .filter(a -> !"CANCELLED".equals(a.status) && !"FAILED".equals(a.status))
                        .filter(a -> a.desiredArrivalTime != null
                                && a.desiredArrivalTime.isAfter(now.minusHours(2)))
                        .filter(a -> a.selectedPathJson != null
                                && !a.selectedPathJson.isBlank()
                                && !"null".equals(a.selectedPathJson.trim()))
                        .min(java.util.Comparator.comparingLong(a ->
                                Math.abs(java.time.Duration.between(now, a.desiredArrivalTime).toSeconds())))
                        .orElse(null);
                if (active == null) {
                    String reply = "대체 경로를 검색할 수 있는 최근 예약이 없어. 경로 정보가 저장된 알람을 새로 예약해 줘.";
                    remember(session, request.message(), reply);
                    return new ChatResponse(sessionId, "RESERVATION_REQUIRED", reply, List.of("reservationId"), null, null);
                }
                outcomes.missed(userId, active.id);
                if (request.startLongitude() != null && request.startLatitude() != null) {
                    var routes = reroute.reroute(new com.buxby.buxby_backend.alarm.MissedBusRouteController.RerouteRequest(
                            active.id, request.startLongitude(), request.startLatitude()), authentication);
                    String reply = formatMissedRoutes(routes);
                    remember(session, request.message(), reply);
                    return new ChatResponse(sessionId, "REROUTED", reply, List.of(), null, active.id);
                }
                session.pendingMissedReservationId = active.id;
                String reply = "놓친 걸 기록했어. 목적지는 " + active.destination
                        + "이야. 현재 위치를 공유하면 대체 경로를 찾아줄게."
                        + " (POST /api/missed-bus/reroute, reservationId=" + active.id + ")";
                remember(session, request.message(), reply);
                return new ChatResponse(sessionId, "LOCATION_REQUIRED", reply,
                        List.of("startLongitude", "startLatitude"), null, active.id);
            }
            updateContext(session, request);
            if (session.startLatitude == null || session.startLongitude == null) {
                homes.findById(userId).ifPresent(home -> {
                    session.startLatitude = home.latitude;
                    session.startLongitude = home.longitude;
                });
            }

            // A confirmation reuses the previous calculated preview. No second ODsay call.
            if (Boolean.TRUE.equals(request.confirmReservation())) {
                if (session.preview == null || session.previewKey == null
                        || !session.previewKey.equals(contextKey(session))) {
                    String question = "먼저 알람 시간을 계산해 줘. 계산 결과를 확인한 뒤 저장할 수 있어.";
                    remember(session, request.message(), question);
                    return new ChatResponse(sessionId, "NEEDS_PREVIEW", question, List.of(), null, null);
                }
                if (!session.preview.wakeTime().isAfter(LocalDateTime.now(KOREA))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "기상 예정시간이 지났습니다. 다시 계산하세요.");
                }
                if (session.savedReservationId != null) {
                    String reply = "이미 저장한 예약이야. 예약 번호는 " + session.savedReservationId + "번이야.";
                    remember(session, request.message(), reply);
                    return new ChatResponse(sessionId, "ALREADY_SAVED", reply, List.of(), session.preview, session.savedReservationId);
                }
                var preview = session.preview;
                AlarmReservation alarm = new AlarmReservation(
                        userId,
                        session.destination == null || session.destination.isBlank() ? "목적지" : session.destination,
                        preview.desiredArrivalTime(),
                        preview.wakeTime(),
                        preview.leaveHomeTime(),
                        preview.routeMinutes(),
                        preview.preparationMinutes()
                );
                alarm.bufferMinutes = learning.recommendedBuffer(userId, session.bufferMinutes == null ? 5 : session.bufferMinutes);
                try {
                    Object selectedPath = preview.route() == null ? null : preview.route().get("selectedPath");
                    if (selectedPath != null) alarm.selectedPathJson = mapper.writeValueAsString(selectedPath);
                } catch (Exception e) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "선택 경로 저장에 실패했습니다.", e);
                }
                alarm.autoCheckStatus = "WAITING";
                AlarmReservation saved = reservations.save(alarm);
                session.savedReservationId = saved.id;
                String reply = "알람 예약 " + saved.id + "번을 저장했어. "
                        + preview.wakeTime() + "에 일어나고 " + preview.leaveHomeTime()
                        + "에 출발하면 돼. 실제 알람은 갤럭시 앱에서 설정해야 해.";
                remember(session, request.message(), reply);
                return new ChatResponse(sessionId, "RESERVED", reply, List.of(), preview, saved.id);
            }

            JsonNode ai = generate(session, request.message());
            String intent = ai.path("intent").asText("CHAT");
            String reply = ai.path("reply").asText("무엇을 도와드릴까요?");
            if (reply.isBlank()) reply = "어떤 교통정보가 필요하신가요?";
            if (reply.length() > 1500) reply = reply.substring(0, 1500);

            if (request.desiredArrivalTime() == null && ai.path("arrivalDateTime").isTextual()) {
                try {
                    LocalDateTime parsed = LocalDateTime.parse(ai.path("arrivalDateTime").asText());
                    session.desiredArrivalTime = parsed;
                } catch (Exception ignored) { }
            }
            if ((request.destination() == null || request.destination().isBlank())
                    && ai.path("destination").isTextual()
                    && !ai.path("destination").asText().isBlank()) {
                session.destination = ai.path("destination").asText();
            }

            // Never trust Gemini-generated coordinates. Resolve names via Kakao Places.
            if (session.destination != null && !session.destination.isBlank()
                    && (session.endLatitude == null || session.endLongitude == null
                    || !session.destination.equals(session.resolvedDestination))) {
                session.endLatitude = null;
                session.endLongitude = null;
                session.destinationCandidates = places.search(session.destination);
                session.resolvedDestination = session.destination;
            }

            boolean planning = intent.equals("ROUTE") || intent.equals("SET_ALARM");
            if (!planning) {
                remember(session, request.message(), reply);
                return new ChatResponse(sessionId, "CHAT", reply, List.of(), null, null);
            }

            if (session.endLatitude == null && !session.destinationCandidates.isEmpty()) {
                List<PlaceSearchService.Place> matches = session.destinationCandidates;
                // Prefer unique exact-name matches; otherwise require explicit place selection.
                List<PlaceSearchService.Place> exact = matches.stream()
                        .filter(p -> p.name().equals(session.destination)).toList();
                if (exact.size() == 1) {
                    session.endLatitude = exact.get(0).latitude();
                    session.endLongitude = exact.get(0).longitude();
                } else if (matches.size() == 1) {
                    session.endLatitude = matches.get(0).latitude();
                    session.endLongitude = matches.get(0).longitude();
                } else if (request.selectedDestinationAddress() != null) {
                    PlaceSearchService.Place selected = matches.stream()
                            .filter(p -> p.address().equals(request.selectedDestinationAddress()))
                            .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "검색 결과에 없는 목적지 주소입니다."));
                    session.endLatitude = selected.latitude();
                    session.endLongitude = selected.longitude();
                } else {
                    StringBuilder question = new StringBuilder("목적지가 여러 개 검색됐어. 주소를 골라줘: ");
                    for (PlaceSearchService.Place place : matches)
                        question.append("[").append(place.name()).append(" / ").append(place.address()).append("] ");
                    String text = question.toString();
                    remember(session, request.message(), text);
                    return new ChatResponse(sessionId, "SELECT_DESTINATION", text,
                            List.of("selectedDestinationAddress"), null, null);
                }
            }

            List<String> missing = new ArrayList<>();
            if (session.startLongitude == null || session.startLatitude == null) missing.add("homeLocation");
            if (session.endLongitude == null || session.endLatitude == null) missing.add("destination");
            if (session.desiredArrivalTime == null) missing.add("desiredArrivalTime");

            if (!missing.isEmpty()) {
                String question = missing.contains("desiredArrivalTime")
                        ? "도착하고 싶은 날짜와 시간을 알려줘."
                        : missing.contains("homeLocation")
                        ? "먼저 집 위치를 등록해 줘. POST /api/ai/home에서 장소명을 입력하면 돼."
                        : "목적지 이름을 알려줘.";
                remember(session, request.message(), question);
                return new ChatResponse(sessionId, "NEEDS_INFO", question, missing, null, null);
            }

            IntegratedJourneyController.JourneyRequest journeyRequest =
                    new IntegratedJourneyController.JourneyRequest(
                            null,
                            session.startLongitude,
                            session.startLatitude,
                            session.endLongitude,
                            session.endLatitude,
                            session.desiredArrivalTime,
                            learning.recommendedBuffer(userId, session.bufferMinutes == null ? 5 : session.bufferMinutes),
                            session.destination,
                            false
                    );
            IntegratedJourneyController.JourneyResponse result = journey.plan(journeyRequest, authentication);

            if (!"PLANNED".equals(result.status())) {
                String response = "NO_ROUTE".equals(result.status())
                        ? "이 구간은 대중교통 경로를 찾지 못했어. 다른 출발지나 목적지를 선택해 줘."
                        : result.message();
                remember(session, request.message(), response);
                return new ChatResponse(sessionId, result.status(), response, List.of(), result, null);
            }

            session.preview = result;
            session.previewKey = contextKey(session);
            session.savedReservationId = null;
            String text = "대중교통 이동 " + result.routeMinutes() + "분, 준비 "
                    + result.preparationMinutes() + "분 기준으로 "
                    + result.leaveHomeTime() + "에 출발하고 "
                    + result.wakeTime() + "에 일어나면 돼. "
                    + "아직 알람은 저장하지 않았어. 확인 후 confirmReservation=true로 저장할 수 있어.";
            remember(session, request.message(), text);
            return new ChatResponse(sessionId, "PLANNED", text, List.of(), result, null);
        }
    }

    private static String formatMissedRoutes(
            com.buxby.buxby_backend.alarm.MissedBusRouteController.RerouteResponse routes) {
        if (!"ROUTES_FOUND".equals(routes.status()) || routes.options().isEmpty()) {
            return "현재 위치에서 대체 경로를 찾지 못했어. 위치를 다시 확인해 줘.";
        }
        StringBuilder message = new StringBuilder("대체 경로(실시간 운행 확인 전):\n");
        for (var option : routes.options()) {
            message.append(option.optionNumber()).append("번: ").append(option.summary())
                    .append(" / 약 ").append(option.travelMinutes()).append("분\n");
        }
        return message.append("실제 운행 여부와 탑승 가능 시간은 별도 확인이 필요해.").toString();
    }

    private static boolean isMissedTransit(String text) {
        if (text == null) return false;
        String normalized = text.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
        boolean transit = normalized.contains("버스") || normalized.contains("지하철")
                || normalized.contains("전철") || normalized.contains("열차");
        boolean missed = normalized.contains("놓쳤") || normalized.contains("놓침")
                || normalized.contains("못탔") || normalized.contains("못탐");
        return transit && missed;
    }

    private static String contextKey(Session s) {
        return String.valueOf(s.startLongitude) + "|" + s.startLatitude + "|"
                + s.endLongitude + "|" + s.endLatitude + "|"
                + s.desiredArrivalTime + "|" + s.bufferMinutes + "|" + s.destination;
    }

    private void updateContext(Session s, ChatController.ChatRequest req) {
        if (req.startLongitude() != null) s.startLongitude = req.startLongitude();
        if (req.startLatitude() != null) s.startLatitude = req.startLatitude();
        if (req.endLongitude() != null) s.endLongitude = req.endLongitude();
        if (req.endLatitude() != null) s.endLatitude = req.endLatitude();
        if (req.desiredArrivalTime() != null) s.desiredArrivalTime = req.desiredArrivalTime();
        if (req.bufferMinutes() != null) s.bufferMinutes = req.bufferMinutes();
        if (req.destination() != null && !req.destination().isBlank()) s.destination = req.destination();
    }

    private JsonNode generate(Session s, String message) {
        String instruction = "너는 한국 대중교통 알람 앱 Buxby의 친절하고 간결한 한국어 챗봇이다. "
                + "현재 한국 시간: " + LocalDateTime.now(KOREA) + ". "
                + "사용자의 의도를 분석하고 오직 JSON 객체를 반환한다. 필드는 "
                + "intent(CHAT, ROUTE, SET_ALARM 중 하나), reply(한국어 답변), "
                + "arrivalDateTime(목표 도착 일시가 확인되면 yyyy-MM-dd'T'HH:mm:ss, 아니면 null), "
                + "destination(명시된 목적지 또는 null)이다. "
                + "경로 검색/출발·기상시간 계산 요청은 ROUTE, 알람 저장 요청은 SET_ALARM이다. "
                + "기존 대화에서 생략된 정보를 참고한다. 오늘/내일은 한국 날짜 기준이다. "
                + "사용자가 명시하지 않은 도착시간이나 주소·좌표를 지어내지 마라. "
                + "정류장, 노선, 실시간 도착시간 및 소요시간을 추측하지 마라. "
                + "실제 경로 검색이나 알람 저장을 수행했다고 말하지 마라. "
                + "교통과 무관한 질문도 일반적인 범위에서 대답할 수 있다.";

        List<Map<String, Object>> contents = new ArrayList<>();
        for (Turn turn : s.turns) {
            contents.add(Map.of("role", turn.role(), "parts", List.of(Map.of("text", turn.text()))));
        }
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", message))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", instruction))));
        body.put("contents", contents);
        body.put("generationConfig", Map.of("responseMimeType", "application/json"));
        URI uri = URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent");
        try {
            String raw = restClient.post().uri(uri)
                    .header("x-goog-api-key", apiKey)
                    .body(body).retrieve().body(String.class);
            JsonNode root = mapper.readTree(raw);
            String responseText = root.path("candidates").path(0).path("content")
                    .path("parts").path(0).path("text").asText("");
            if (responseText.isBlank()) throw new IllegalStateException("Gemini empty response");
            JsonNode parsed = mapper.readTree(responseText);
            if (!parsed.isObject()) throw new IllegalStateException("Gemini invalid JSON");
            return parsed;
        } catch (RestClientResponseException e) {
            // Do not echo provider responses: they may include implementation details.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Gemini API 요청에 실패했습니다 (HTTP " + e.getStatusCode().value() + ").", e);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini 응답 처리에 실패했습니다.", e);
        }
    }

    private static boolean validSessionId(String sessionId) {
        if (sessionId == null) return false;
        try {
            UUID.fromString(sessionId);
            return sessionId.length() == 36;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void remember(Session session, String userMessage, String botReply) {
        session.turns.addLast(new Turn("user", userMessage));
        session.turns.addLast(new Turn("model", botReply));
        while (session.turns.size() > MAX_TURNS) session.turns.removeFirst();
    }
}
