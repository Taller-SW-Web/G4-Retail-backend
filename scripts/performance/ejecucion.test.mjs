import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { ejecutar } from './pos.mjs';

// Dobles del transporte para verificar el generador; NO son mediciones del backend.
const carrito = { lineas: [{}], moneda: 'PEN', subtotalBruto: 118, descuentoTotal: 0, totalPagar: 118, baseImponible: 100, montoIgv: 18 };
function cuerpo(url) {
  if (url.includes('calcular')) return carrito;
  if (url.includes('codigo-barras')) return { codigoBarras: '7750101000012', variante: { sku: 'ZAPVEL-38-NEG' }, precio: 249 };
  return { items: [{}], total: 1 };
}
async function salida() {
  await mkdir('target/pruebas-generador', { recursive: true });
  return mkdtemp(resolve('target/pruebas-generador/run-'));
}
test('el generador guarda muestras, etapas e interpretación sin guardar tokens', async t => {
  t.mock.method(globalThis, 'fetch', async url => ({ status: 200, json: async () => cuerpo(url) }));
  const destino = await salida();
  const aprobado = await ejecutar({ PERF_OUTPUT: destino, PERF_SECONDS: '1', PERF_WARMUP_SECONDS: '1', PERF_USERS: '1', PERF_THINK_MS: '1' });
  assert.equal(aprobado, true);
  const texto = await readFile(resolve(destino, 'resultados.json'), 'utf8');
  const informe = JSON.parse(texto);
  assert.equal(informe.etapas.length, 1);
  assert.equal(informe.etapas[0].resultados.length, 3);
  assert.ok(informe.etapas[0].muestras.length >= 60);
  assert.equal(texto.includes('Authorization'), false);
  assert.equal(texto.includes('eyJhbGci'), false);
  assert.match(await readFile(resolve(destino, 'informe.md'), 'utf8'), /no demuestra/);
});
test('un servidor que responde 401 no genera una medición aprobada', async t => {
  t.mock.method(globalThis, 'fetch', async () => ({ status: 401, json: async () => ({}) }));
  const destino = await salida();
  await writeFile(resolve(destino, 'resultados.json'), '{"aprobado":true}');
  await writeFile(resolve(destino, 'informe.md'), 'Resultado anterior');
  await assert.rejects(ejecutar({ PERF_OUTPUT: destino }), /Preflight falló/);
  const diagnostico = JSON.parse(await readFile(resolve(destino, 'preflight.json'), 'utf8'));
  assert.equal(diagnostico.muestra.status, 401);
  await assert.rejects(readFile(resolve(destino, 'resultados.json')), /ENOENT/);
  await assert.rejects(readFile(resolve(destino, 'informe.md')), /ENOENT/);
});
