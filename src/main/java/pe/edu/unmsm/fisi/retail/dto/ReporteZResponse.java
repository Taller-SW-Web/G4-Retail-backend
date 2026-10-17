package pe.edu.unmsm.fisi.retail.dto;

import pe.edu.unmsm.fisi.retail.model.CajaSesion;

import java.math.BigDecimal;

/**
 * Resultado del arqueo ciego: el Reporte Z del turno (RF-22).
 *
 * <p>Es la respuesta de {@code POST /api/v1/retail/caja/turnos/{turnoId}/cierre} y el único momento
 * en que la SPA llega a ver {@code saldoSistema}: recién después de que el cajero declaró su conteo.
 * Antes de eso el número no se expone en ningún endpoint, que es lo que hace ciego al arqueo.</p>
 *
 * <p>El documento de reparto de la Semana 8 lo lista como {@code ReporteZResponse} y pide
 * explícitamente el manejo de <strong>sobrantes y faltantes</strong>: eso es {@code diferencia} y su
 * traducción a los estados {@code CERRADA} (cuadra) y {@code OBSERVADA} (hay sobrante o faltante).</p>
 *
 * <p>No incluye la ruta de un PDF: el ticket de 80 mm se compone e imprime en el navegador
 * ({@code ZReport.tsx}), que es donde vive el área imprimible.</p>
 *
 * @param turnoId             turno cerrado
 * @param saldoInicial        fondo fijo con el que arrancó el turno
 * @param ventasEfectivoTotal cobros en efectivo del turno (ver pendiente con F5 en CajaService)
 * @param ingresosMenores     suma de ingresos menores de caja chica
 * @param egresosMenores      suma de egresos menores de caja chica
 * @param saldoSistema        lo que el sistema esperaba en gaveta
 * @param saldoDeclarado      lo que el cajero contó
 * @param diferencia          {@code saldoDeclarado - saldoSistema}; negativo es faltante, positivo sobrante
 * @param estado              {@code CERRADA} si cuadra, {@code OBSERVADA} si hay diferencia
 */
public record ReporteZResponse(
        String turnoId,
        BigDecimal saldoInicial,
        BigDecimal ventasEfectivoTotal,
        BigDecimal ingresosMenores,
        BigDecimal egresosMenores,
        BigDecimal saldoSistema,
        BigDecimal saldoDeclarado,
        BigDecimal diferencia,
        String estado) {

    /** Arma el Reporte Z a partir del turno ya actualizado. */
    public static ReporteZResponse desde(CajaSesion sesion, BigDecimal ventasEfectivoTotal,
                                         BigDecimal ingresosMenores, BigDecimal egresosMenores,
                                         BigDecimal saldoSistema) {
        return new ReporteZResponse(
                sesion.getIdSesion().toString(),
                sesion.getSaldoInicialEfectivo(),
                ventasEfectivoTotal,
                ingresosMenores,
                egresosMenores,
                saldoSistema,
                sesion.getSaldoFinalDeclarado(),
                sesion.getDiferenciaSaldo(),
                sesion.getEstado().name());
    }
}
