# Buxby - 기존 카카오 회원 이메일 보완 패치

## 목적
기존 KAKAO 회원의 `app_user.email`이 `NULL` 또는 공백이고, 카카오 응답에서 유효한 이메일을 새로 받은 경우에만 이메일을 보완합니다.

## 동작 규칙
- 신규 회원: `email`이 있으면 `AppUser` 생성자에 전달해 저장합니다. 이메일이 없으면 기존처럼 `NULL`로 저장할 수 있습니다.
- 기존 회원 + DB 이메일이 `NULL`/공백 + 카카오 이메일 있음: 이메일을 보완하고 `save()`합니다.
- 기존 회원 + DB 이메일이 이미 존재: 절대 덮어쓰지 않고 저장도 다시 호출하지 않습니다.
- 기존 회원 + 카카오 이메일이 없거나 공백: 기존 정보를 그대로 유지합니다.
- 회원 식별은 기존과 동일하게 `(provider, providerId)`를 사용합니다.

## 포함 파일
- `KakaoOAuth2UserService.java`: `exists...` 확인 후 `find...` 기반으로 신규/기존 회원 처리를 분리합니다.
- `AppUser.java`: `updateEmailIfMissing()` 도메인 메서드를 추가합니다.
- `KakaoOAuth2UserServiceTests.java`: 신규 회원 저장, 기존 NULL/공백 이메일 보완, 기존 이메일 보존, 이메일 미수신, ID 누락을 확인하는 테스트 7개를 추가합니다.

## 적용 방법
1. 백엔드를 종료합니다.
2. 현재 Buxby 백엔드 폴더를 백업합니다.
3. ZIP 안의 `Buxby_Backend` 폴더를 `C:\Users\<사용자>\Documents\Github` 아래에 압축 해제하고 파일을 병합/덮어쓰기합니다.
4. **현재 카카오 scope 설정은 덮어쓰지 않습니다.** `application.properties`는 이 패치에 포함되어 있지 않습니다. 기존 `profile_nickname,account_email` 설정을 유지하세요.
5. 아래 테스트를 실행합니다.

```powershell
cd C:\Users\<사용자>\Documents\Github\Buxby_Backend
.\gradlew.bat test --tests "com.buxby.buxby_backend.auth.KakaoOAuth2UserServiceTests"
```

전체 테스트 실행:

```powershell
.\gradlew.bat test
```

## 수동 확인
이미 이메일이 있는 회원은 일반 로그인 후에도 이메일이 바뀌지 않아야 합니다. 기존 이메일이 NULL인 테스트 회원으로 로그인하면 카카오가 이메일을 반환하는 경우에만 기존 행의 이메일이 채워집니다.

```sql
SELECT ID, EMAIL, NICKNAME, PROVIDER, PROVIDER_ID
FROM APP_USER
WHERE PROVIDER = 'KAKAO'
ORDER BY ID DESC;
```

주의: `Repository.save()` 호출 및 정적 테스트 통과는 DB 커밋 성공을 대체하지 않습니다. 실제 H2 결과도 확인하세요.
