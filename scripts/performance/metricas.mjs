// Percentil por rango más cercano. Los errores también cuentan en las latencias.
export function percentil(valores, porcentaje) {
  if (!valores.length) return null;
  const ordenados = [...valores].sort((a, b) => a - b);
  return ordenados[Math.max(0, Math.ceil(porcentaje / 100 * ordenados.length) - 1)];
}

export function resumir(muestras, segundos, limiteMs) {
  const tiempos = muestras.map(m => m.ms);
  const errores = muestras.filter(m => !m.ok).length;
  const p95 = percentil(tiempos, 95);
  return {
    peticiones: muestras.length, errores,
    tasaError: muestras.length ? errores / muestras.length : null,
    rps: muestras.length / segundos,
    p50: percentil(tiempos, 50), p95, p99: percentil(tiempos, 99),
    max: tiempos.length ? tiempos.reduce((max, tiempo) => Math.max(max, tiempo), 0) : null,
    fueraDeLimite: tiempos.filter(t => t >= limiteMs).length,
    limiteP95Ms: limiteMs,
    aprobado: muestras.length >= 20 && errores / muestras.length < 0.01 && p95 < limiteMs,
  };
}

const centavos = valor => Math.round(valor * 100);
export function carritoValido(json) {
  const campos = ['subtotalBruto', 'descuentoTotal', 'totalPagar', 'baseImponible', 'montoIgv'];
  return campos.every(c => typeof json?.[c] === 'number' && Number.isFinite(json[c]))
    && json.moneda === 'PEN' && json.totalPagar > 0
    && Array.isArray(json.lineas) && json.lineas.length > 0
    && centavos(json.totalPagar) === centavos(json.subtotalBruto) - centavos(json.descuentoTotal)
    && centavos(json.totalPagar) === centavos(json.baseImponible) + centavos(json.montoIgv)
    && centavos(json.baseImponible) === centavos(json.totalPagar / 1.18);
}
