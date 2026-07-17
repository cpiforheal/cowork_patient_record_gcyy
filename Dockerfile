FROM node:22-alpine AS frontend-build
WORKDIR /build/frontend
ENV HUSKY=0
RUN npm install --global pnpm@10.27.0
COPY coshare_patientrecord_sys_frontend/Geeker-Admin/package.json \
     coshare_patientrecord_sys_frontend/Geeker-Admin/pnpm-lock.yaml \
     coshare_patientrecord_sys_frontend/Geeker-Admin/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY coshare_patientrecord_sys_frontend/Geeker-Admin/ ./
RUN pnpm build-only:pro

FROM maven:3.9.11-eclipse-temurin-17 AS backend-build
WORKDIR /build/backend
COPY coshare_patientrecord_sys_backend/pom.xml ./
COPY coshare_patientrecord_sys_backend/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=backend-build /build/backend/target/coshare_patientrecord_sys-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --from=frontend-build /build/frontend/dist /app/frontend
RUN mkdir -p /data/attachments
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
