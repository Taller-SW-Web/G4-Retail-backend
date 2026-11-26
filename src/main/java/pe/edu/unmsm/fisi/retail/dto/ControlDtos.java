package pe.edu.unmsm.fisi.retail.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Respuestas de la torre de control (F11), contrato v0.5 §5.11.
 */
public final class ControlDtos {

    private ControlDtos() {
    }

    /**
     * {@code GET /control/resumen}. Un contador es {@code null} si su fuente no respondió; esa fuente
     * aparece en {@code fuentesNoDisponibles}.
     */
    public record ResumenControlResponse(
            Integer pickupPendientes,
            Integer stockCritico,
            Integer campanasVigentes,
            List<String> fuentesNoDisponibles,
            OffsetDateTime actualizadoEn) {
    }

    /**
     * {@code GET /control/stock-critico} (RF-29).
     *
     * @param nivel        {@code AGOTADO} o {@code CRITICO}
     * @param stockTienda  unidades en tienda; {@code null} si Productos no expone cantidades (X-P0-03)
     * @param stockCentral unidades en el almacén central; {@code null} si no se conocen
     */
    public record VarianteCriticaResponse(
            String sku,
            String productoId,
            String nombreProducto,
            String talla,
            String color,
            String nivel,
            Integer stockTienda,
            Boolean disponibleEnCentral,
            Integer stockCentral) {
    }

    /** {@code GET /control/campanas-vigentes} (RF-31). */
    public record CampanaVigenteResponse(
            String campanaId,
            String titulo,
            String descripcion,
            String tipoDescuento,
            BigDecimal valor,
            String codigoCupon,
            BigDecimal montoMinimo,
            String condiciones,
            OffsetDateTime vigenteDesde,
            OffsetDateTime vigenteHasta) {
    }
}
