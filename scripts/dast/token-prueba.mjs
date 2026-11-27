// Emite un JWT HS256 efimero (30 min) para el escaneo DAST contra la instancia local de pruebas.
// La clave es la de desarrollo del perfil local; nunca se usa contra produccion.
import { createHmac } from 'node:crypto';

const CLAVE_DESARROLLO = 'clave-de-desarrollo-g4-retail-solo-para-la-semana-8'; // gitleaks:allow — clave mock del perfil local
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');

export function tokenPrueba(ahora = Math.floor(Date.now() / 1000)) {
  const sinFirma = `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ sub: 'bbbbbbbb-0000-0000-0000-000000000001', iat: ahora, exp: ahora + 1800 })}`;
  const firma = createHmac('sha256', CLAVE_DESARROLLO).update(sinFirma).digest('base64url');
  return `${sinFirma}.${firma}`;
}

if (process.argv[1]?.endsWith('token-prueba.mjs')) {
  process.stdout.write(tokenPrueba());
}
