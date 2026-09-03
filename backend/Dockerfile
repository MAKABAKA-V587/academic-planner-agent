# ---- 构建阶段：Maven 编译打包 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 先只复制 pom 拉取依赖，利用 Docker 层缓存：依赖不变时改代码不重新下载
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

COPY src ./src
RUN mvn package -DskipTests -B

# ---- 运行阶段：仅 JRE，镜像更小 ----
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/target/student-agent-0.0.1-SNAPSHOT.jar app.jar

ENV TZ=Asia/Shanghai
EXPOSE 8080

# MaxRAMPercentage：容器内按内存限制比例设堆上限，避免 JVM 感知不到 cgroup 限额
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
