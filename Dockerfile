# 1단계: jar 빌드
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# build.gradle이 바뀔 때만 라이브러리 jar를 받는다 (src만 바뀐 커밋은 이 층을 캐시에서 쓴다)
RUN chmod +x gradlew && ./gradlew --no-daemon downloadDependencies
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# 2단계: 실행 이미지 (JRE만)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENV SPRING_PROFILES_ACTIVE=prod
# 알람 시간은 한국 시간 기준이다 (Render 기본값은 UTC)
ENV TZ=Asia/Seoul
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]
