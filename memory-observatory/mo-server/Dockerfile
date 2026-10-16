# 多阶段构建：JDK 21 编译，JRE 21 运行（与 Spring Boot 3.4 兼容）
# M3（步骤 13）：mo-server 依赖同仓模块 mo-contract / mo-workbench / mo-support ，故构建上下文改为
# **仓库根**（见 docker-compose.yml 的 build.context），用 `-pl mo-server -am` 连带构建依赖模块。
# mo-exoskeleton 自 2026-10-12 起也依赖 mo-support（共用一份 ApiKeyAuthFilter），同构改用根上下文。
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY mo-server/settings.xml /root/.m2/settings.xml
# 先只拷各模块 pom，预热依赖（最大化利用 Docker 层缓存）
COPY pom.xml .
COPY mo-contract/pom.xml mo-contract/
COPY mo-workbench/pom.xml mo-workbench/
COPY mo-support/pom.xml mo-support/
COPY mo-server/pom.xml mo-server/
COPY mo-exoskeleton/pom.xml mo-exoskeleton/
RUN mvn -B -q -pl mo-server -am dependency:go-offline
# 再拷源码构建
COPY mo-contract/src mo-contract/src
COPY mo-workbench/src mo-workbench/src
COPY mo-support/src mo-support/src
COPY mo-server/src mo-server/src
RUN mvn -B -q -pl mo-server -am package -DskipTests

FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/mo-server/target/mo-server-0.1.0.jar app.jar
# 「一键接入」安装源：把纯 stdlib 的接入层整包带进镜像，安装时拷到宿主 ~/.qoder/hooks。
# 不依赖用户本地是否有仓库（C 端场景）。
COPY mo_ingest /app/mo_ingest
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]