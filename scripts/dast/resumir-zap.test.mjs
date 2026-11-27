import test from 'node:test';
import assert from 'node:assert/strict';
import { crearResumen, parsearReporte, resumirAlertas } from './resumir-zap.mjs';

test('un JSON vacío o sin sitios no acredita un escaneo', () => {
  for (const value of ['{}', '{"site":[]}', '{"site":[{}]}']) {
    assert.throws(() => parsearReporte(value));
  }
});

test('acepta el JSON de ZAP aunque tenga una marca BOM', () => {
  assert.deepEqual(parsearReporte('\uFEFF{"site":[{"alerts":[]}]}'), { site: [{ alerts: [] }] });
});

test('resume alertas por riesgo e instancias del reporte de ZAP', () => {
  const alertas = resumirAlertas({
    site: [{
      alerts: [
        { riskdesc: 'Alto (3)', instances: [{}, {}] },
        { riskdesc: 'Alto (3)', instances: [{}] },
        { riskcode: '1', instances: [] },
      ],
    }],
  });

  assert.deepEqual(alertas, [
    { riesgo: 'Alto (3)', alertas: 2, instancias: 3 },
    { riesgo: 'Bajo', alertas: 1, instancias: 0 },
  ]);
});

test('no presenta un reporte ausente ni un paso fallido como resultado aprobado', () => {
  assert.match(crearResumen({ sha: 'abc', outcome: 'failure', image: 'zap@sha256:1', alertas: [], errorReporte: '' }), /NO APROBADO/);
  assert.match(crearResumen({ sha: 'abc', outcome: 'success', image: '', alertas: [], errorReporte: 'archivo ausente' }), /INCOMPLETO/);
});
