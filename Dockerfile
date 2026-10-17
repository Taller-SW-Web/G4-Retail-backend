# ── Etapa 1: Construccion con Maven ──────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copia primero solo el POM para cachear las dependencias en una capa de Docker.
# Si el POM no cambia, Docker reutiliza esta capa y no descarga todo otra vez.
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copia el codigo fuente y compila (sin ejecutar tests; ya pasaron en CI).
COPY src ./src
RUN mvn clean package -DskipTests -B

# ── Etapa 2: Imagen de ejecucion ligera ─────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copia unicamente el JAR resultante de la etapa anterior.
COPY --from=build /app/target/*.jar app.jar

# Render y Railway asignan el puerto via la variable de entorno PORT.
# Spring Boot lo lee gracias a server.port=${PORT:8080} en application.properties.
ENV PORT=8080
EXPOSE ${PORT}

# Variables de entorno requeridas en Railway / Render:
#   SPRING_PROFILES_ACTIVE=supabase,mock
#   SUPABASE_DB_URL=jdbc:postgresql://<host>:5432/postgres?sslmode=require
#   SUPABASE_DB_USER=postgres (o postgres.<ref> si se usa pooler)
#   SUPABASE_DB_PASSWORD=...
#   RETAIL_CORS_ORIGENES_PERMITIDOS=https://g4-retail-frontend-gamma.vercel.app
#   JAVA_TOOL_OPTIONS=-Xms128m -Xmx384m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC
ENTRYPOINT ["java", "-jar", "app.jar"]

