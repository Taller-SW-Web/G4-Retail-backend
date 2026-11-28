# Smoke de rendimiento del POS

El workflow central ejecuta el generador sobre un backend efimero en loopback,
con H2 y mocks. No valida latencia de servicios G5/G6/G7 reales ni PostgreSQL.
El generador rechaza ejecuciones externas, incluyendo produccion.

## Configuracion y alcance

- 1, 5 y 10 usuarios virtuales; 60 segundos por etapa y 5 de calentamiento.
- PERF_MAX_RPM=120 y PERF_VENDOR_MAX_RPM=120: se aplica el menor limite global.
- Tres endpoints distribuidos mediante un contador compartido, no por worker.
- A 120 RPM, 60 segundos permiten aproximadamente 120 muestras totales,
  unas 40 por endpoint. El anterior valor de 20 segundos solo permitia unas 13.
- Minimo 20 muestras por endpoint; errores < 1%; p95 < 800 ms para catalogo
  y escaneo, < 500 ms para carrito. No se modificaron estos umbrales.
- Conserva p50/p95/p99, muestras, errores, RPS, RPM configurado, limite efectivo
  y RPM alcanzado. Una duracion personalizada insuficiente produce NO-GO.

Es un smoke con tasa limitada y varios usuarios virtuales, no una prueba de
capacidad concurrente: la cola global limita las llegadas incluso con 10 usuarios.
La latencia excluye la espera en esa cola e incluye respuesta y validacion JSON.

## Ejecucion local

Con el backend efimero local/mocks disponible:

```sh
node scripts/performance/pos.mjs
node --test scripts/performance/*.test.mjs
```

Los tests del generador usan transporte simulado; no son mediciones del backend.
En Actions, descargar performance-<attempt>: resultados.json, informe.md y logs.
Los reportes se conservan con always() incluso cuando falla la medicion.
