package pe.edu.unmsm.fisi.retail.integracion.productos.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Campaña comercial de Productos y Ofertas (G6), tal como la consume Retail en F11 (RF-31).
 *
 * @param canales       canales donde aplica, por ejemplo {@code RETAIL} o {@code MARKETPLACE}
 * @param tipoDescuento {@code PORCENTAJE}, {@code MONTO_FIJO} o {@code NXM}
 * @param codigoCupon   cupón que el vendedor inyecta en el carrito; {@code null} si no requiere cupón
 * @param montoMinimo   monto mínimo de compra; {@code null} si no tiene
 */
public record CampanaComercial(
        String campanaId,
        String titulo,
        String descripcion,
        Set<String> canales,
        String tipoDescuento,
        BigDecimal valor,
        String codigoCupon,
        BigDecimal montoMinimo,
        String condiciones,
        OffsetDateTime vigenteDesde,
        OffsetDateTime vigenteHasta) {

    public CampanaComercial {
        canales = canales == null ? Set.of() : Set.copyOf(canales);
    }

    /** {@code true} si aplica al canal y su vigencia incluye el instante dado. */
    public boolean vigentePara(String canal, OffsetDateTime instante) {
        return canales.contains(canal)
                && (vigenteDesde == null || !instante.isBefore(vigenteDesde))
                && (vigenteHasta == null || !instante.isAfter(vigenteHasta));
    }
}
