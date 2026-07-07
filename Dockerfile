# Stage 1: Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom.xml and fetch dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source files and package
COPY src ./src
RUN mvn package -DskipTests -B

# Download and cache Hugging Face ONNX model and vocab
RUN mkdir -p .model_cache \
    && curl -L https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/vocab.txt -o .model_cache/vocab.txt \
    && curl -L https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx -o .model_cache/model.onnx

# Stage 2: Runtime stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Install curl and sqlite3
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    sqlite3 \
    && rm -rf /var/lib/apt/lists/*

# Copy shaded JAR
COPY --from=build /app/target/synapse-backend-1.0-SNAPSHOT.jar app.jar

# Copy models cache
COPY --from=build /app/.model_cache ./.model_cache

# Copy static frontend assets
COPY index.html .
COPY waiting.gif .
COPY approach_deck.html .

EXPOSE 8000

ENV PORT=8000

CMD ["java", "-jar", "app.jar"]
