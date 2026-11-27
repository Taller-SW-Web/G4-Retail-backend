import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { tokenPrueba } from './token-prueba.mjs';

test('token de prueba DAST: HS256 valido, vendedor de pruebas y expiracion de 30 minutos', () => {
  const token = tokenPrueba(1000);
  const [cabecera, cuerpo, firma] = token.split('.');
  assert.deepEqual(JSON.parse(Buffer.from(cabecera, 'base64url')), { alg: 'HS256', typ: 'JWT' });
  const claims = JSON.parse(Buffer.from(cuerpo, 'base64url'));
  assert.equal(claims.sub, 'bbbbbbbb-0000-0000-0000-000000000001');
  assert.equal(claims.exp - claims.iat, 1800);
  const esperada = createHmac('sha256', 'clave-de-desarrollo-g4-retail-solo-para-la-semana-8').update(`${cabecera}.${cuerpo}`).digest('base64url'); // gitleaks:allow — clave mock del perfil local
  assert.equal(firma, esperada);
});
