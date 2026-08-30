# Java 21 기반의 가벼운 이미지 사용 (eclipse-temurin 권장)
FROM eclipse-temurin:21-jre-alpine

# 작업 디렉토리 설정
WORKDIR /app

# 빌드 결과물 중 실행 가능한 jar 파일만 복사
ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar

# 한국 시간대 설정
ENV TZ=Asia/Seoul
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 배포 프로필(prod)을 적용하여 실행
# G1GC를 명시한다 — 컨테이너 메모리 1300MiB는 JVM의 server-class 판정 문턱(1792MiB)
# 미만이라 옵션 없이는 SerialGC가 자동 선택된다. 1300MiB에서 두 GC를 실측 비교한
# 결과 G1이 p95·p99·CPU 쓰로틀 전 지표에서 우세했다.
ENTRYPOINT ["java", "-XX:+UseG1GC", "-Dspring.profiles.active=prod", "-jar", "app.jar"]