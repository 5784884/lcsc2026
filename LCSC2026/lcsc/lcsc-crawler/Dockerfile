FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app
# 这里的名字要和你 target 目录下的 jar 包名一致
COPY target/lcsc-crawler-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]