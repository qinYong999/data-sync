# ============================================================================
# DataSync 生产镜像（多阶段构建）
#
# 构建：docker build -t datasync:1.0.0 .
# 运行：见 docker-compose.yml（生产必须用 prod profile，否则会拒绝启动）
#
# 【注意】本机没有 Docker，这个文件**未经本机实测**，属于交付但未验证的产物。
#        详见 docs/production-readiness/04-acceptance-report.md 的"未验证项"。
# ============================================================================

# ---------- 阶段 1：构建前端 ----------
FROM node:22-alpine AS frontend
WORKDIR /build
# 先只拷贝依赖清单，让 npm ci 这一层能被缓存
COPY data-sync-web/package.json data-sync-web/package-lock.json ./
RUN npm ci
COPY data-sync-web/ ./
RUN npm run build

# ---------- 阶段 2：构建后端并内嵌前端 ----------
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
# 先把 POM 单独拷进来预热依赖，源码改动不会导致重新下载依赖
COPY pom.xml ./
COPY data-sync-core/pom.xml data-sync-core/
COPY data-sync-server/pom.xml data-sync-server/
RUN mvn -B -pl data-sync-server -am dependency:go-offline -DskipTests || true

COPY data-sync-core/src data-sync-core/src
COPY data-sync-server/src data-sync-server/src
# 前端产物落到 Spring Boot 的静态资源目录，最终由同一个 JAR 提供页面与 /api
COPY --from=frontend /build/dist/ data-sync-server/src/main/resources/static/
RUN mvn -B -pl data-sync-server -am package -DskipTests

# ---------- 阶段 3：运行时 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# 时区与字符集：容器默认 UTC，元数据库写的是 Asia/Shanghai，不设置会导致时间字段偏移 8 小时
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

RUN groupadd -r datasync && useradd -r -g datasync datasync \
    && mkdir -p /app/logs && chown -R datasync:datasync /app
USER datasync

COPY --from=backend /build/data-sync-server/target/data-sync-server-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

# 容器内存感知：让 JVM 按 cgroup 限额自动算堆，而不是按宿主机物理内存
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

# 健康检查直接打 Spring Boot Actuator（已在 Security 里放行匿名访问）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD ["sh", "-c", "wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '\"status\":\"UP\"'"]
