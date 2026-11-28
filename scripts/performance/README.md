# Performance POS — Miguel Alonso Solis Cunza

Carga HTTP reproducible con Node 22, sin instalar dependencias. Tres endpoints del contrato:
catálogo, código de barras y cálculo de carrito. No crea ventas, no cobra ni altera stock.

## Ejecutar

Desde backend, con Java 21, Maven Wrapper y Node disponibles:

```powershell
./scripts/performance/ejecutar-local.ps1
```

En GitHub: Actions → **Performance POS S14** → Run workflow. También se ejecuta automáticamente
después de aprobar integración en **Evidencias Backend S14**, tanto en PR como en despliegue
normal a main. Descargar el artefacto `performance-s14-*`.
Cada ejecución verifica y empaqueta el backend antes de medirlo.

Si el backend ya está levantado en local/mocks con G5 deshabilitado:

```powershell
node scripts/performance/pos.mjs
```

Resultados en `target/performance/resultados.json` (muestras, equipo, versión, etapas)
y `informe.md` (percentiles, errores, RPS, umbrales e interpretación).
Si falla el preflight se guarda su diagnóstico, sin reportar una medición aprobada.

## Diseño de la medición

### Límite de solicitudes y protección de G5

El rate limit normal del backend **permanece habilitado** durante las pruebas; no se debe modificar temporalmente para conseguir una medición artificial. Cada vendedor admite 120 solicitudes/minuto y G5 admite 300 solicitudes/minuto compartidas entre todas las cajas. El generador serializa el inicio de cada solicitud en una cola global y limita a `min(PERF_MAX_RPM, PERF_VENDOR_MAX_RPM)`: en modo externo usa 120 RPM por defecto (5 segundos entre inicios), no 300, para respetar el límite del vendedor. El máximo absoluto configurable es 300 RPM, pero elevarlo requiere coordinar ventanas y carga de todos los vendedores; cambiar una variable no eleva la cuota real de G5 ni la del vendedor.

Para una medición externa, no habilitar concurrencia distribuida ni ejecutar otra prueba simultánea con el mismo vendedor. El generador reporta RPM configuradas, p95/p99, RPS alcanzado y errores. La tasa es un límite de inicio de solicitudes agregado a todos los workers/endpoints del proceso; no representa una tasa constante de llegada.

Variables: `PERF_MAX_RPM` (default externo 240, local 120), `PERF_VENDOR_MAX_RPM` (default 120; máximo permitido 300). Efectivo = el menor de ambos. Por tanto externo queda por defecto a **120 RPM** debido al límite por vendedor. No existe bypass de rate limit de la aplicación.

- Calentamiento de 5 segundos excluido de estadísticas.
- Etapas de 1, 5 y 10 usuarios, 20 segundos por etapa; mezcla equitativa de tres endpoints.
- Modelo cerrado, pausa de 50 ms y timeout de 5 segundos por petición.
- Gate por endpoint y etapa: mínimo 20 muestras, errores < 1%, p95 < 800 ms para catálogo/escaneo.
- Carrito: p95 < 500 ms, objetivo operativo **propuesto**, no requisito del contrato.
- RF-04 exige respuesta < 800 ms. Se registra además cuántas peticiones exceden el límite;
  aprobar p95 no significa que todas lo cumplan.
- Se valida JSON y coherencia de importes, además del HTTP 200.
- No mide renderizado de React, cobro, stock bajo concurrencia ni capacidad máxima de producción.
- Local usa H2 y ProductosMock; no equivale a prueba de integraciones remotas.

Variables: `PERF_USERS=1,5,10`, `PERF_SECONDS=20`, `PERF_WARMUP_SECONDS=5`,
`PERF_THINK_MS=50`, `PERF_TIMEOUT_MS=5000`, `PERF_OUTPUT=target/performance`.
Para comparar ejecuciones mantener los mismos datos, equipo, logging y parámetros.

## Staging con integraciones reales

Solo después de que Mihael habilite sus adaptadores y confirme datos de prueba:

```powershell
$env:PERF_MODE = 'externo'
$env:PERF_BASE_URL = 'https://URL-STAGING-RETAIL'
$env:PERF_ENVIRONMENT = 'staging; indicar versiones y dependencias efectivamente conectadas'
$env:PERF_BARCODE = 'CODIGO-DE-PRUEBA-EXISTENTE'
$env:PERF_SKU = 'SKU-QUE-CORRESPONDE-AL-CODIGO'
# Inyectar PERF_TOKEN por gestor de secretos o entorno; no pegarlo en documentación.
node scripts/performance/pos.mjs
```

El token de desarrollo se restringe a loopback. El modo externo exige HTTPS y un token
de vendedor. No se guardan tokens ni cuerpos de respuesta. La etiqueta de entorno es
declarada por quien ejecuta: no demuestra por sí misma que G6/G7 sean reales.
Conservar también configuración/redacción de secretos y trazas de correlación del despliegue.

## Interpretación para la sustentación

Comparar p95, errores y RPS entre etapas. Si RPS deja de crecer mientras p95 aumenta,
investigar CPU, consultas, pools y tiempo de los módulos externos. Un fallo de autenticación
o fixture es fallo de precondición, no evidencia de rendimiento. No comparar un arranque
frío de Render con el tramo caliente local como si fueran el mismo escenario.
