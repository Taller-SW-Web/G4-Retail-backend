import test from 'node:test';
import assert from 'node:assert/strict';
import { percentil, resumir, carritoValido } from './metricas.mjs';
import { configuracion } from './pos.mjs';

test('no presenta una ejecución vacía ni insuficiente como aprobada', () => {
  assert.equal(resumir([], 1, 800).aprobado, false);
  assert.equal(resumir([{ ms: 1, ok: true }], 1, 800).aprobado, false);
});
test('percentiles por rango cercano, sin modificar la muestra', () => {
  const valores = [400, 100, 300, 200];
  assert.equal(percentil(valores, 50), 200);
  assert.equal(percentil(valores, 95), 400);
  assert.deepEqual(valores, [400, 100, 300, 200]);
});
test('latencia lenta y errores HTTP impiden pasar el gate', () => {
  assert.equal(resumir(Array.from({ length: 20 }, () => ({ ms: 801, ok: true })), 2, 800).aprobado, false);
  assert.equal(resumir(Array.from({ length: 20 }, () => ({ ms: 1, ok: false })), 2, 800).aprobado, false);
  const bien = resumir(Array.from({ length: 20 }, () => ({ ms: 10, ok: true })), 2, 800);
  assert.equal(bien.aprobado, true); assert.equal(bien.rps, 10);
});
test('un 200 con importes o IGV incorrectos no es una respuesta válida', () => {
  const json = { lineas: [{}], moneda: 'PEN', subtotalBruto: 118, descuentoTotal: 0, totalPagar: 118, baseImponible: 100, montoIgv: 18 };
  assert.equal(carritoValido(json), true);
  assert.equal(carritoValido({ ...json, montoIgv: 17 }), false);
  assert.equal(carritoValido({ ...json, baseImponible: 99, montoIgv: 19 }), false);
  assert.equal(carritoValido({}), false);
});
test('el token de desarrollo no puede enviarse a un servidor externo', () => {
  assert.throws(() => configuracion({ PERF_BASE_URL: 'https://example.com' }), /loopback/);
  assert.throws(() => configuracion({ PERF_MODE: 'externo', PERF_BASE_URL: 'https://example.com' }), /HTTPS/);
});
test('valida duración, concurrencia y URLs sin credenciales', () => {
  assert.throws(() => configuracion({ PERF_SECONDS: 'NaN' }), /rango/);
  assert.throws(() => configuracion({ PERF_USERS: '0,1000' }), /usuarios/);
  assert.throws(() => configuracion({ PERF_BASE_URL: 'http://user:password@localhost' }), /credenciales/);
  assert.deepEqual(configuracion({}).concurrencias, [1, 5, 10]);
});
