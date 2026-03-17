FROM eclipse-temurin:21-jdk-alpine AS builder

RUN apk add --no-cache maven curl

WORKDIR /app

COPY pom.xml ./
COPY src ./src
COPY mvnw ./
RUN chmod +x mvnw

RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl jq

WORKDIR /app

COPY --from=builder /app/target/quarkus-app/ /app/
COPY run.sh ./

EXPOSE 8080

CMD ["sh", "run.sh"]
