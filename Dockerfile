# ── 1단계: 빌드 (JDK) ──────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# 의존성 받기는 소스가 바뀌어도 다시 하지 않도록 먼저 한다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src src
# 테스트는 GitHub Actions에서 이미 돌렸다. 여기서는 실행 파일(jar)만 만든다.
RUN ./gradlew --no-daemon bootJar -x test

# ── 2단계: 실행 (JRE만) ─────────────────────────────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app

# root가 아닌 계정으로 실행한다
RUN useradd --system --uid 1001 --create-home app \
    && mkdir -p /app/uploads \
    && chown -R app:app /app
USER app

COPY --from=build --chown=app:app /workspace/build/libs/*-SNAPSHOT.jar app.jar

ENV APP_UPLOAD_DIR=/app/uploads
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
