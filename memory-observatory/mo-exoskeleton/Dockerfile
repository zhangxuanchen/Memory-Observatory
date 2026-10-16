# 多阶段构建：JDK 21 编译，JRE 21 运行
# 2026-10-12：外骨骼开始依赖同仓模块 mo-support（共用一份 ApiKeyAuthFilter，经 support 传递引入
# mo-contract），不再能"以本目录为上下文"独立构建——构建上下文改为**仓库根**
# （见 docker-compose.yml 的 build.context），用 `-pl mo-exoskeleton -am` 连带构建依赖模块，
# 与 mo-server/Dockerfile 同构。
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY mo-exoskeleton/settings.xml /root/.m2/settings.xml
# 先只拷各模块 pom，预热依赖（reactor 需要全部模块 pom 在场）
COPY pom.xml .
COPY mo-contract/pom.xml mo-contract/
COPY mo-workbench/pom.xml mo-workbench/
COPY mo-support/pom.xml mo-support/
COPY mo-server/pom.xml mo-server/
COPY mo-exoskeleton/pom.xml mo-exoskeleton/
RUN mvn -B -q -pl mo-exoskeleton -am dependency:go-offline
# 再拷源码构建（只需 -am 闭包内的三个模块的 src）
COPY mo-contract/src mo-contract/src
COPY mo-support/src mo-support/src
COPY mo-exoskeleton/src mo-exoskeleton/src
RUN mvn -B -q -pl mo-exoskeleton -am package -DskipTests

FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/mo-exoskeleton/target/mo-exoskeleton-0.1.0.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
