import { createHmac } from 'node:crypto';
import { mkdir, writeFile, rm } from 'node:fs/promises';
import { cpus, totalmem, platform, release } from 'node:os';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as esperar } from 'node:timers/promises';
import { carritoValido, resumir } from './metricas.mjs';

export function configuracion(env) {
  const modo = env.PERF_MODE || 'local';
  const url = new URL(env.PERF_BASE_URL || 'http://127.0.0.1:8080');
  if (url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new Error('PERF_BASE_URL debe ser solo el origen, sin credenciales ni parámetros');
  }
  const local = ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
  if (!['local', 'externo'].includes(modo)) throw new Error('PERF_MODE debe ser local o externo');
  if (modo === 'local' && !local) throw new Error('El token de prueba solo se utiliza en loopback');
  if (modo === 'externo' && (!env.PERF_TOKEN || url.protocol !== 'https:')) {
    throw new Error('Modo externo requiere HTTPS y PERF_TOKEN de un vendedor de pruebas');
  }
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('Protocolo no admitido');
  function entero(nombre, defecto, min, max) {
    const n = Number(env[nombre] ?? defecto);
    if (!Number.isInteger(n) || n < min || n > max) throw new Error(`${nombre} fuera de rango`);
    return n;
  }
  const concurrencias = (env.PERF_USERS || '1,5,10').split(',').map(Number);
  if (!concurrencias.length || concurrencias.length > 5 || concurrencias.some(n => !Number.isInteger(n) || n < 1 || n > 50)) {
    throw new Error('PERF_USERS admite hasta 5 etapas de 1 a 50 usuarios');
  }
  if (modo === 'externo' && (!env.PERF_BARCODE || !env.PERF_SKU || !env.PERF_ENVIRONMENT)) {
    throw new Error('Modo externo requiere PERF_BARCODE, PERF_SKU y PERF_ENVIRONMENT');
  }
  return {
    modo, origen: url.origin, concurrencias,
    duracion: entero('PERF_SECONDS', 60, 1, 300),
    calentamiento: entero('PERF_WARMUP_SECONDS', 5, 1, 60),
    pausaMs: entero('PERF_THINK_MS', 50, 0, 5000),
    // Budget shared by every virtual user and endpoint. External mode is conservative
    // by default because G5's 300 RPM is shared across all stores/registers.
    maxRpm: entero('PERF_MAX_RPM', modo === 'externo' ? 240 : 120, 1, 300),
    vendedorMaxRpm: entero('PERF_VENDOR_MAX_RPM', 120, 1, 300),
    timeoutMs: entero('PERF_TIMEOUT_MS', 5000, 100, 30000),
    barcode: env.PERF_BARCODE || '7750101000012',
    sku: env.PERF_SKU || 'ZAPVEL-38-NEG',
    entorno: modo === 'local' ? 'local,mock; H2; G6 fixture; G5 deshabilitado' : env.PERF_ENVIRONMENT,
    salida: resolve(env.PERF_OUTPUT || 'target/performance'),
  };
}

function tokenLocal() {
  const codificar = valor => Buffer.from(JSON.stringify(valor)).toString('base64url');
  const ahora = Math.floor(Date.now() / 1000);
  const cuerpo = `${codificar({ alg: 'HS256', typ: 'JWT' })}.${codificar({
    sub: 'bbbbbbbb-0000-0000-0000-000000000001', iat: ahora, exp: ahora + 3600,
  })}`;
  // Clave pública de desarrollo ya presente en application.properties; no es un secreto real.
  return `${cuerpo}.${createHmac('sha256', 'clave-de-desarrollo-g4-retail-solo-para-la-semana-8').update(cuerpo).digest('base64url')}`;
}

export async function ejecutar(env = process.env) {
  const c = configuracion(env);
  if (c.modo !== 'local') throw new Error('Carga permitida únicamente en loopback efímero; producción prohibida');
  const token = c.modo === 'local' ? tokenLocal() : env.PERF_TOKEN;
  const escenarios = [
    { nombre: 'catalogo', ruta: '/api/v1/retail/catalogo?page=0&size=20', limite: 800,
      validar: j => Array.isArray(j?.items) && j.items.length > 0 && j.total >= j.items.length },
    { nombre: 'escaneo', ruta: `/api/v1/retail/catalogo/codigo-barras/${encodeURIComponent(c.barcode)}`, limite: 800,
      validar: j => j?.codigoBarras === c.barcode && j?.variante?.sku === c.sku && j.precio > 0 },
    { nombre: 'carrito', ruta: '/api/v1/retail/carritos/calcular', limite: 500,
      cuerpo: JSON.stringify({ items: [{ sku: c.sku, cantidad: 2 }] }), validar: carritoValido },
  ];
  const intervaloMinimoMs = Math.ceil(60_000 / Math.min(c.maxRpm, c.vendedorMaxRpm));
  let proximaPeticion = 0;
  async function esperarTurno() {
    const ahora = Date.now();
    const turno = Math.max(ahora, proximaPeticion);
    proximaPeticion = turno + intervaloMinimoMs;
    if (turno > ahora) await esperar(turno - ahora);
  }
  async function peticion(escenario) {
    await esperarTurno();
    const inicio = performance.now();
    let status = 0, ok = false, error = null;
    try {
      const respuesta = await fetch(c.origen + escenario.ruta, {
        method: escenario.cuerpo ? 'POST' : 'GET', redirect: 'error',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: escenario.cuerpo, signal: AbortSignal.timeout(c.timeoutMs),
      });
      status = respuesta.status;
      const cuerpo = await respuesta.json();
      ok = status === 200 && escenario.validar(cuerpo);
      if (!ok) error = status === 200 ? 'RESPUESTA_INVALIDA' : `HTTP_${status}`;
    } catch (e) { error = e.name; }
    return { escenario: escenario.nombre, ms: performance.now() - inicio, status, ok, error };
  }
  await mkdir(c.salida, { recursive: true });
  console.log(`[PERF] Modo=${c.modo}; usuarios=${c.concurrencias.join(',')}; max=${Math.min(c.maxRpm, c.vendedorMaxRpm)} RPM global; intervalo mínimo=${intervaloMinimoMs} ms.`);
  console.log(`[PERF] G5 comparte 300 RPM entre cajas; este generador limita todas las peticiones a una sola cola global. No se desactiva el rate limit del backend.`);
  // Evita presentar resultados de un run anterior si el preflight actual falla.
  for (const archivo of ['resultados.json', 'informe.md', 'preflight.json']) {
    await rm(resolve(c.salida, archivo), { force: true });
  }
  // El preflight falla antes de generar carga con credenciales/fixtures incorrectos.
  console.log('[PERF] Preflight: valida autenticación, disponibilidad y respuesta de cada endpoint; no cuenta como medición.');
  for (const escenario of escenarios) {
    console.log(`[PERF] Preflight ${escenario.nombre}...`);
    const muestra = await peticion(escenario);
    if (!muestra.ok) {
      await writeFile(resolve(c.salida, 'preflight.json'), JSON.stringify({ fecha: new Date().toISOString(), muestra }, null, 2));
      throw new Error(`Preflight falló en ${escenario.nombre}: ${muestra.error}; no se midió performance`);
    }
  }
  async function etapa(usuarios, segundos, guardar) {
    const inicio = performance.now(), fin = inicio + segundos * 1000;
    const muestras = [];
    let siguienteEscenario = 0;
    await Promise.all(Array.from({ length: usuarios }, async (_, usuario) => {
      while (performance.now() < fin) {
        const muestra = await peticion(escenarios[siguienteEscenario++ % escenarios.length]);
        if (guardar) muestras.push(muestra);
        if (c.pausaMs) await esperar(c.pausaMs);
      }
    }));
    return { muestras, segundos: (performance.now() - inicio) / 1000 };
  }
  await etapa(1, c.calentamiento, false);
  console.log(`[PERF] Calentamiento de ${c.calentamiento}s completado; muestras excluidas del reporte.`);
  const etapas = [];
  for (const usuarios of c.concurrencias) {
    console.log(`[PERF] Inicia etapa: ${usuarios} usuarios virtuales durante ${c.duracion}s; ${Math.min(c.maxRpm, c.vendedorMaxRpm)} RPM máximo total.`);
    const { muestras, segundos } = await etapa(usuarios, c.duracion, true);
    const resultados = escenarios.map(e => ({ escenario: e.nombre,
      ...resumir(muestras.filter(m => m.escenario === e.nombre), segundos, e.limite) }));
    etapas.push({ usuarios, segundos, resultados, muestras, rpmConfigurado: c.maxRpm, rpmEfectivoMaximo: Math.min(c.maxRpm, c.vendedorMaxRpm), rpmAlcanzado: muestras.length * 60 / segundos, rps: muestras.length / segundos });
    console.log(`Etapa ${usuarios} usuarios: ${resultados.every(r => r.aprobado) ? 'APROBADA' : 'NO APROBADA'}`);
  }
  let sha = env.GITHUB_SHA || 'sin-git', cambiosLocales = 'desconocido';
  try {
    sha = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
    cambiosLocales = Boolean(execFileSync('git', ['status', '--porcelain'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim());
  } catch { /* El informe conserva la ausencia de procedencia Git. */ }
  const informe = {
    fecha: new Date().toISOString(), sha, cambiosLocales, tipo: 'smoke con tasa limitada; no demuestra capacidad concurrente',
    run: env.GITHUB_RUN_ID || null, configuracion: c,
    equipo: { plataforma: platform(), version: release(), node: process.version, cpu: cpus()[0]?.model, nucleos: cpus().length, memoriaBytes: totalmem() },
    aprobado: etapas.every(e => e.resultados.every(r => r.aprobado)), etapas,
  };
  const f = n => n == null ? 'N/D' : n.toFixed(2);
  const filas = etapas.flatMap(e => e.resultados.map(r =>
    `| ${e.usuarios} | ${r.escenario} | ${r.peticiones} | ${r.errores} | ${f(r.tasaError * 100)} | ${f(r.p50)} | ${f(r.p95)} | ${f(r.p99)} | ${f(r.rps)} | ${r.fueraDeLimite} | ${r.aprobado ? 'PASA' : 'FALLA'} |`));
  const markdown = `# Performance POS — S14\n\nFecha UTC: ${informe.fecha}\n\nSHA: ${sha}; cambios locales: ${cambiosLocales}.\n\nEntorno declarado: ${c.entorno}\n\nResultado: ${informe.aprobado ? 'PASA' : 'FALLA'}.\n\nLímite global del generador: ${Math.min(c.maxRpm, c.vendedorMaxRpm)} RPM; intervalo mínimo ${intervaloMinimoMs} ms entre peticiones. Rate limit del backend permanece activo. G5 permite 300 RPM compartidas: la prueba externa usa como máximo 240 RPM por defecto y debe coordinarse con otras cajas.\n\n`
    + etapas.map(e => `Usuarios: ${e.usuarios}; RPM configurado: ${e.rpmConfigurado}; l?mite efectivo: ${e.rpmEfectivoMaximo}; RPM alcanzado: ${f(e.rpmAlcanzado)}.\n\n`).join('')
    + '| Usuarios | Endpoint | Peticiones | Errores | Errores % | p50 ms | p95 ms | p99 ms | RPS | >= límite ms | Gate |\n'
    + '|---|---|---|---|---|---|---|---|---|---|---|\n' + filas.join('\n')
    + '\n\nSmoke con tasa limitada, no prueba de capacidad concurrente. Carga cerrada: cada usuario espera respuesta y pausa. RPS medido, no tasa de llegada constante. '
    + 'Calentamiento excluido; latencia incluye lectura y validación de JSON. Cada endpoint exige al menos 20 muestras, '
    + 'errores < 1% y p95 < 800 ms (catálogo/escaneo) o < 500 ms (carrito, objetivo operativo propuesto). '
    + 'La spec RF-04 exige < 800 ms: la columna de excedencias muestra incumplimientos que el p95 podría ocultar. '
    + 'Un gate aprobado no demuestra que todas las peticiones cumplan el límite ni capacidad máxima. '
    + 'La carga local mide H2 y mocks; no demuestra latencia de G5/G6/G7, PostgreSQL o nube. '
    + 'Repetir en staging, comparar p95 y RPS entre etapas; si p95 crece y RPS se estanca, investigar saturación.\n';
  await writeFile(resolve(c.salida, 'resultados.json'), JSON.stringify(informe, null, 2));
  await writeFile(resolve(c.salida, 'informe.md'), markdown);
  return informe.aprobado;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  ejecutar().then(ok => { process.exitCode = ok ? 0 : 1; }).catch(error => {
    console.error(error.message); process.exitCode = 1;
  });
}
