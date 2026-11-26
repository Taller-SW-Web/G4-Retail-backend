# DAST OWASP ZAP — Semana 14

El workflow `DAST API OWASP ZAP S14` ejecuta un **API Scan activo** de ZAP contra el OpenAPI que
publica este backend. Complementa SonarCloud, CodeQL y Dependency Review: estos analizan código o
dependencias; ZAP analiza respuestas HTTP de una instancia en ejecución.

## Ejecución y alcance

- En PR hacia `develop` o `main` y en el despliegue normal a `main`, `Evidencias Backend S14`
  empaqueta una vez la aplicación después de integración y comparte el JAR con DAST y performance.
  DAST no vuelve a ejecutar Maven ni las suites.
- También puede iniciarse manualmente desde **Actions → DAST API OWASP ZAP S14 → Run workflow**.
  En ese caso empaqueta con `-DskipTests`; el log de esa preparación se conserva por separado.
- Arranca exclusivamente en `127.0.0.1:8080` con `local,mock`, H2 en memoria, G5 deshabilitado y
  pickup/pedidos en memoria. No recibe URL de staging ni producción y no apunta a Railway,
  Supabase, G5, G6, G7 o pagos.
- Antes del escaneo, comprueba `/v3/api-docs` y `GET /api/v1/retail/perfil` con un JWT HS256 efímero
  del vendedor semilla. El token existe solo durante el job y no se guarda como artefacto.

La instancia es descartable: el API Scan puede emitir solicitudes activas, incluidas operaciones
mutantes definidas por OpenAPI. Por ese motivo no debe reutilizarse este flujo contra servicios no
autorizados ni datos no efímeros.

## Política y resultados

La política [`.zap/api-scan-rules.tsv`](../../.zap/api-scan-rules.tsv) bloquea Path Traversal, RFI,
CRLF, manipulación de parámetros, XSS, SQLi, inyección de código/SO, XXE y EL. Los demás avisos
siguen apareciendo en los reportes sin bloquear por sí solos. La opción `-I` hace que los WARN no
produzcan un falso bloqueo; no oculta hallazgos ni cambia las reglas marcadas como `FAIL`.

ZAP devuelve `0` al completar sin `FAIL`, `1` ante al menos un `FAIL`, `2` ante WARN sin `FAIL` y
`3` ante error de ejecución. Con esta política, el wrapper falla el job ante `1` y ante `3`; el
resumen conserva la distinción entre un reporte no aprobado y una ejecución incompleta. No se usa
`continue-on-error` ni `|| true` para convertir fallos en éxito.

Cada ejecución publica `dast-zap-s14-*` con `resumen.md`, HTML, JSON y Markdown de ZAP cuando los
genera, preflights y `servidor.log`. El resumen registra SHA, fecha UTC, imagen/digest ZAP,
modalidad, entorno, alcance y conteos. La falta de JSON es **incompleta**, nunca “sin
vulnerabilidades”.

## Límites actuales

El token de prueba solo acredita las rutas que ZAP logra importar y ejecutar en el backend local.
No acredita autenticación de G7, autorización por todos los roles, SPA, lógica de negocio ni las
integraciones reales pendientes. Un escaneo a staging requiere una autorización, URL, datos de
prueba, rol y alcance documentados antes de implementarse.
