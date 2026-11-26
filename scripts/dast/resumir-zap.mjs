import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';

const nombresRiesgo = new Map([
  ['0', 'Informativo'],
  ['1', 'Bajo'],
  ['2', 'Medio'],
  ['3', 'Alto'],
]);

export function parsearReporte(contenido) {
  return JSON.parse(contenido.replace(/^\uFEFF/, ''));
}

export function resumirAlertas(reporte) {
  const sitios = Array.isArray(reporte?.site) ? reporte.site : [];
  const alertas = sitios.flatMap(sitio => Array.isArray(sitio.alerts) ? sitio.alerts : []);
  const resumen = new Map();

  for (const alerta of alertas) {
    const riesgo = alerta.riskdesc || nombresRiesgo.get(String(alerta.riskcode)) || 'Sin clasificar';
    const actual = resumen.get(riesgo) || { alertas: 0, instancias: 0 };
    actual.alertas += 1;
    actual.instancias += Array.isArray(alerta.instances) ? alerta.instances.length : 0;
    resumen.set(riesgo, actual);
  }

  return [...resumen.entries()].map(([riesgo, totales]) => ({ riesgo, ...totales }));
}

export function crearResumen({ sha, outcome, image, alertas, errorReporte }) {
  const tieneReporte = !errorReporte;
  const estado = tieneReporte && outcome === 'success'
    ? 'COMPLETADO: el escaneo terminó sin hallazgos configurados como bloqueantes.'
    : tieneReporte
      ? 'NO APROBADO: el paso de ZAP falló; el reporte adjunto sirve para diagnosticar el resultado.'
      : `INCOMPLETO: no se produjo un JSON de ZAP (${errorReporte}).`;
  const filas = alertas.length
    ? alertas.map(alerta => `| ${alerta.riesgo} | ${alerta.alertas} | ${alerta.instancias} |`).join('\n')
    : '| Sin alertas registradas | 0 | 0 |';

  return `# Resumen DAST OWASP ZAP\n\n`
    + `- Estado: **${estado}**\n`
    + `- SHA evaluado: \`${sha || 'no disponible'}\`\n`
    + `- Fecha UTC: \`${new Date().toISOString()}\`\n`
    + `- Imagen ZAP: \`${image || 'no disponible'}\`\n`
    + '- Modalidad: API Scan OpenAPI **activo**; no se usa `-S` ni se declara como escaneo pasivo.\n'
    + '- Entorno: Spring Boot local en `127.0.0.1`, perfil `local,mock`, H2 en memoria, G5 deshabilitado y pickup/pedidos en memoria.\n'
    + '- Autenticación: JWT HS256 efímero del vendedor semilla; el preflight exige `GET /api/v1/retail/perfil` antes de iniciar ZAP.\n'
    + '- Alcance: rutas importadas desde `http://127.0.0.1:8080/v3/api-docs`; no se apunta a Railway, Supabase ni G5/G6/G7.\n'
    + '- Bloqueo: Path Traversal, RFI, CRLF, manipulación de parámetros, XSS, SQLi, inyección de código/SO, XXE y EL; los demás avisos permanecen en el reporte sin bloquear por sí solos.\n\n'
    + '## Alertas registradas\n\n'
    + '| Riesgo | Reglas | Instancias |\n'
    + '| --- | ---: | ---: |\n'
    + `${filas}\n\n`
    + 'Los reportes HTML, JSON y Markdown de ZAP, el log del servidor y los preflights se publican junto a este resumen. '
    + 'La ausencia de reporte se interpreta como ejecución incompleta, no como ausencia de vulnerabilidades.\n';
}

function argumentos(argumentosLinea) {
  const valores = {};
  for (let indice = 0; indice < argumentosLinea.length; indice += 2) {
    const clave = argumentosLinea[indice];
    const valor = argumentosLinea[indice + 1];
    if (!clave?.startsWith('--') || valor === undefined) throw new Error('Argumentos inválidos');
    valores[clave.slice(2)] = valor;
  }
  return valores;
}

async function ejecutar() {
  const opciones = argumentos(process.argv.slice(2));
  let reporte;
  let errorReporte = '';

  try {
    reporte = parsearReporte(await readFile(opciones.report, 'utf8'));
  } catch (error) {
    errorReporte = error.code === 'ENOENT' ? 'archivo ausente' : 'archivo inválido';
  }

  await mkdir(dirname(opciones.output), { recursive: true });
  await writeFile(opciones.output, crearResumen({
    sha: opciones.sha,
    outcome: opciones.outcome,
    image: opciones.image,
    alertas: reporte ? resumirAlertas(reporte) : [],
    errorReporte,
  }));
}

if (process.argv[1]?.endsWith('resumir-zap.mjs')) {
  ejecutar().catch(error => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
