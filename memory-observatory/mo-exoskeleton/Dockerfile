# 多阶段构建：JDK 21 编译，JRE 21 运行
# 与 mo-server/Dockerfile 同构——本模块的 pom 独立（parent 直指 spring-boot-starter-parent，
# 不依赖根聚合 pom），故构建上下文只需本目录。
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/mo-exoskeleton-0.1.0.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]