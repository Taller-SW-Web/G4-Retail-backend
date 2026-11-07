package pe.edu.unmsm.fisi.retail.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Respuesta de {@code POST /api/v1/retail/cambios}: el cambio aprobado y el vale emitido (F9, RF-25).
 *
 * @param solicitudId       identificador del cambio registrado
 * @param estadoAprobacion  {@code APROBADO_EN_TIENDA}
 * @param destinoSugerido   reingreso al inventario o merma; es una sugerencia, no mueve stock
 * @param montoAcreditado   saldo a favor del cliente
 * @param observaciones     notas de la inspeccion que conviene que el vendedor vea
 * @param vale              el vale de compra, listo para el ticket termico
 */
public record ValeCompraResponse(
        String solicitudId,
        String estadoAprobacion,
        String destinoSugerido,
        BigDecimal montoAcreditado,
        List<String> observaciones,
        Vale vale) {

    /**
     * @param codigoVale       {@code VALE-2026-00001}
     * @param codigoBarras     contenido del codigo de barras impreso; hoy coincide con el codigo
     * @param boletaOrigen     comprobante que origino el vale
     * @param fechaEmision     ISO 8601 UTC
     * @param fechaVencimiento ISO 8601 UTC
     */
    public record Vale(
            String codigoVale,
            BigDecimal monto,
            String codigoBarras,
            String boletaOrigen,
            String fechaEmision,
            String fechaVencimiento,
            String estado) {
    }
}
