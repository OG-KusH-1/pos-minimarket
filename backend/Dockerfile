# Imagen de la API (Spring Boot, Java 21). Contexto de build: ./backend
# Requiere backend/mvnw, backend/.mvn y backend/pom.xml (T3).

# ---- Etapa 1: compilacion ------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Primero wrapper y pom: la capa de dependencias se cachea mientras no cambie el pom.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src src
RUN ./mvnw -q -DskipTests package \
    && cp target/*.jar /workspace/app.jar

# ---- Etapa 2: runtime ----------------------------------------------------
FROM eclipse-temurin:21-jre
ENV TZ=UTC
WORKDIR /app

# Usuario sin privilegios. /data/files es el volumen de archivos (ADR-09); se
# crea con el dueno correcto para que un volumen nombrado nuevo lo herede.
RUN groupadd --system --gid 10001 pos \
    && useradd --system --uid 10001 --gid pos --no-create-home pos \
    && mkdir -p /data/files \
    && chown -R pos:pos /data /app

COPY --from=build --chown=pos:pos /workspace/app.jar app.jar

USER pos
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
