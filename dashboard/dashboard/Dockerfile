FROM node:20-alpine AS fe
WORKDIR /app
COPY frontend/package.json .
RUN npm install
COPY frontend/ .
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY backend/pom.xml .
RUN mvn -q dependency:go-offline
COPY backend/src ./src
COPY --from=fe /app/dist ./src/main/resources/static
RUN mvn -q package -DskipTests

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates tar \
 && rm -rf /var/lib/apt/lists/* \
 && KV=$(curl -Ls https://dl.k8s.io/release/stable.txt) \
 && curl -Lo /usr/local/bin/kubectl "https://dl.k8s.io/release/${KV}/bin/linux/amd64/kubectl" && chmod +x /usr/local/bin/kubectl \
 && curl -L https://github.com/vmware-tanzu/velero/releases/download/v1.18.4/velero-v1.18.4-linux-amd64.tar.gz | tar -xz -C /tmp \
 && mv /tmp/velero-v1.18.4-linux-amd64/velero /usr/local/bin/velero
COPY --from=build /app/target/recovery-api-1.0.jar /app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
