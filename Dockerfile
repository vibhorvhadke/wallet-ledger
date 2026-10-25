# ---------- Stage 1: build the JAR ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Download dependencies first (cached unless pom.xml changes)
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

# Build (tests run in CI, not during the image build)
COPY src src
RUN ./mvnw -q -B package -DskipTests

# ---------- Stage 2: small runtime image ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /app/target/wallet-ledger-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]