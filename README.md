# G4-Retail-backend

Backend del **Módulo Retail (G4)**, el punto de venta de mostrador: `ms-retail`.
Atiende a la SPA del vendedor y se apoya en Seguridad (G7), Productos (G6) y Ventas (G5), que en la
Semana 8 se simulan con mocks controlados (§6 del contrato de API).

Fuente de verdad: [`G4-Retail-specs`](https://github.com/Taller-SW-Web/G4-Retail-specs) →
`specs/api-contracts.md` y `AGENTS.md`.

## Stack

| Pieza | Elección |
|---|---|
| Lenguaje | Java 21 |
| Framework | Spring Boot 3.5.16 |
| Persistencia | Spring Data JPA + Hibernate |
| Base de datos | PostgreSQL en Supabase (perfil `supabase`) · H2 en memoria (perfil `local`) |
| Build | Maven Wrapper (`./mvnw`), no hace falta instalar Maven |
| Pruebas | JUnit 5 + Spring Boot Test |

## Cómo levantarlo

```bash
./mvnw test                  # compila y corre las pruebas
./mvnw spring-boot:run       # arranca en http://localhost:8080 con el perfil 'local'
```

El perfil `local` usa H2 en memoria y carga `datos-local.sql`, así que **no necesitas credenciales
para trabajar**. La consola de la base queda en `http://localhost:8080/h2-console`
(JDBC URL `jdbc:h2:mem:retail`, usuario `sa`, sin contraseña).

## Configuración contra Supabase

Las credenciales **nunca se escriben en el repositorio**: se leen de variables de entorno. Un
secreto que entra al historial de Git sigue ahí aunque se borre después, y esta base es compartida.

```bash
export SUPABASE_DB_URL="jdbc:postgresql://<host>:5432/postgres?sslmode=require"
export SUPABASE_DB_USER="<usuario>"
export SUPABASE_DB_PASSWORD="<contraseña>"
./mvnw spring-boot:run -Dspring-boot.run.profiles=supabase
```

En este perfil `ddl-auto=validate`: Hibernate compara las entidades con el esquema real y falla si
no coinciden, pero nunca modifica la base de datos.

## Estructura en capas

```
pe.edu.unmsm.fisi.retail
├─ controller/    Endpoints REST, rutas y códigos HTTP
├─ service/       Lógica de negocio, cálculo de IGV (18%) y validaciones
├─ repository/    Interfaces JpaRepository
├─ model/         Entidades JPA
├─ dto/           Request y Response, independientes de las entidades
├─ exception/     Errores de negocio y GlobalExceptionHandler (RFC 7807)
├─ integracion/   Adaptadores a G5, G6 y G7, con mock y real intercambiables por perfil
└─ config/        CORS y configuración transversal
```

Los errores salen siempre en `application/problem+json` (decisión D-01):

```json
{ "type": "https://retail.g4/problemas/codigo-barras-no-encontrado",
  "title": "Recurso no encontrado", "status": 404, "code": "CODIGO_BARRAS_NO_ENCONTRADO",
  "detail": "...", "instance": "/api/v1/retail/catalogo/codigo-barras/7751234567890",
  "correlationId": "...", "timestamp": "..." }
```

## Estado actual

Está el esqueleto: proyecto base, capas, entidad `RET_PERSONAL_TIENDA`, repositorio, manejo de
errores, CORS y perfiles de base de datos.

**`controller/` está vacío a propósito.** La guía de la Semana 7 prohíbe programar un endpoint antes
de que Miguel y Maylle confirmen que está aprobado en `specs/api-contracts.md`, y el tablero §8 del
contrato v0.4 marca los cuatro endpoints de Retail como **PROPUESTO**, no APROBADO:

| Endpoint | F | Estado |
|---|---|---|
| `GET /api/v1/retail/perfil` | F1 | PROPUESTO |
| `GET /api/v1/retail/health` | F10 | PROPUESTO |
| `GET /api/v1/retail/catalogo` | F2 | PROPUESTO |
| `GET /api/v1/retail/catalogo/codigo-barras/{codigo}` | F2 | PROPUESTO |

### Desfases pendientes de coordinar

| Tema | Detalle | Responsable |
|---|---|---|
| `tienda_id` | El contrato lo fija como texto (`TIENDA-MIRAFLORES`, D-05); `schema.sql` lo tiene como `UUID`. La entidad sigue hoy al esquema real. | Angie |
| Perfil `CAJERO` | D-08 lo elimina y deja solo `VENDEDOR`; el CHECK de `schema.sql` aún lo admite. | Angie |
| Login | D-02 quita `/auth/login` del backend: lo atiende Seguridad (G7) y en Semana 8, su mock. | Maylle y Mihael |
| Rutas | D-03 fija el prefijo único `/api/v1/retail/*`, en lugar de `/productos` y `/ordenes`. | Maylle y Miguel |

## Despliegue en la Nube (Railway / Render)

El backend cuenta con soporte para despliegue automatizado en Railway y Render mediante Dockerfile multi-etapa y `railway.json`.

### Variables de Entorno de Producción
| Variable | Descripción / Ejemplo |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `supabase,mock` (o `supabase` si todos los módulos externos están en vivo) |
| `SUPABASE_DB_URL` | `jdbc:postgresql://<host>:5432/postgres?sslmode=require` |
| `SUPABASE_DB_USER` | `postgres` (o `postgres.<project-ref>` si se usa connection pooler) |
| `SUPABASE_DB_PASSWORD` | Contraseña de la base de datos |
| `RETAIL_CORS_ORIGENES_PERMITIDOS` | `https://g4-retail-frontend-gamma.vercel.app` |
| `JAVA_TOOL_OPTIONS` | `-Xms128m -Xmx384m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC` |

El endpoint de verificación de salud para Railway/Render está disponible públicamente en `GET /api/v1/retail/health`.

## Ramas

`main` es la entrega formal y no recibe commits directos. Todo PR apunta a `develop`. El trabajo
diario va en `feat/`, `fix/`, `docs/` o `infra/`.

