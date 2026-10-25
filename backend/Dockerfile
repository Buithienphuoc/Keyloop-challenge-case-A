# Service Scheduler API image.
#   docker build -t service-scheduler-api backend
# Stage 1 compiles and packages the Play app with sbt; stage 2 is a small JRE image that only
# holds the packaged app and runs it as a non-root user.

FROM eclipse-temurin:21-jdk AS build
ARG SBT_VERSION=1.10.7
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
 && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" | tar -xz -C /opt \
 && rm -rf /var/lib/apt/lists/*
ENV PATH="/opt/sbt/bin:${PATH}" SCHEDULER_SLIM=true
WORKDIR /src
# Dependencies first, so editing application code does not download them again.
COPY build.sbt ./
COPY project/build.properties project/plugins.sbt project/
RUN sbt -batch update
COPY app app
COPY conf conf
RUN sbt -batch stage

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /app scheduler
WORKDIR /app
COPY --from=build --chown=scheduler /src/target/universal/stage /app
USER scheduler
ENV PORT=8081 LOG_FORMAT=json
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS "http://localhost:${PORT}/health" || exit 1
# Play writes a RUNNING_PID file by default; a container does not need one.
ENTRYPOINT ["/app/bin/service-scheduler", "-Dpidfile.path=/dev/null", "-J-XX:MaxRAMPercentage=75"]
