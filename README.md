# Buxby_Backend

IT창업실무 A조 버스비 백엔드입니다. Spring Boot 4 · Java 21

## 로컬 실행

환경 변수를 넣고 `./gradlew bootRun`. 로컬은 메모리 DB(H2)를 써서 재시작하면 데이터가 지워집니다.

## 환경 변수

`.env` 등 키가 든 파일은 git에 올리지 않습니다.

| 이름 | 설명 |
|---|---|
| `KAKAO_REST_API_KEY`, `KAKAO_CLIENT_SECRET` | 카카오 로그인 |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | 구글 로그인 |
| `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` | 네이버 로그인 |
| `ODSAY_API_KEY` | 대중교통 경로 검색 |
| `TAGO_API_KEY` | 공공데이터포털 키 (TAGO 버스도착·버스정류소정보, 서울특별시 버스도착정보) |
| `GEMINI_API_KEY` | AI 해석 (선택) |

### 배포 (Render, Docker)

`Dockerfile`로 빌드하며 이미지에서 `prod` 프로필이 켜집니다. 위 키에 더해 다음을 넣습니다.

| 이름 | 예시 | 설명 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://호스트:5432/buxby` | PostgreSQL JDBC 주소 |
| `DB_USERNAME`, `DB_PASSWORD` | | DB 계정 |
| `FRONTEND_URL` | `https://buxby.vercel.app` | 간편 로그인 후 돌아갈 프론트 주소 |
| `KAKAO_REDIRECT_URI` 등 | `https://buxby.vercel.app/login/oauth2/code/kakao` | 선택. 비우면 요청 주소 기준으로 만듭니다 |

- 프론트(Vercel)가 `/api`, `/oauth2`, `/login/oauth2` 요청을 이 서버로 중계하는 구성을 기준으로 합니다. `prod`에서는 중계 헤더(`X-Forwarded-*`)로 원래 주소를 알아내 간편 로그인 콜백 주소를 만듭니다.
- 각 로그인 제공자 콘솔에 `https://프론트도메인/login/oauth2/code/{kakao|google|naver}`를 등록해야 합니다.
- `prod`에서는 H2 콘솔과 Swagger가 꺼집니다.
