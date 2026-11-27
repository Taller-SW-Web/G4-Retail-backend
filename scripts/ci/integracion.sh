#!/usr/bin/env bash
# Suite de integracion de Retail invocada por el job central `integration` de devsecops-templates.
# El template provee Java 21, Node 22 y PostgreSQL 16 efimero (CI_POSTGRES_URL/USER/PASSWORD).
set -euo pipefail

: "${CI_POSTGRES_URL:?CI_POSTGRES_URL lo provee el template central}"

if command -v mvn >/dev/null 2>&1; then maven=(mvn); else maven=(bash ./mvnw); fi

# Pruebas de los scripts de evidencia (performance y DAST).
node --test scripts/performance/*.test.mjs scripts/dast/*.test.mjs

# Integracion con H2 y dobles HTTP.
"${maven[@]}" -B -Pintegracion-s14 test

# Integracion del POS contra PostgreSQL 16 efimero.
S14_DB_URL="$CI_POSTGRES_URL" \
S14_DB_USER="$CI_POSTGRES_USER" \
S14_DB_PASSWORD="$CI_POSTGRES_PASSWORD" \
  "${maven[@]}" -B -Dtest=PosDependenciasIntegrationTest \
  -Dretail.test.reports=target/postgres-reports test
