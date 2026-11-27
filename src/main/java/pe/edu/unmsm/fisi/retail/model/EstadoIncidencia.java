package pe.edu.unmsm.fisi.retail.model;

import java.util.List;

/**
 * Estado local de una incidencia de inventario (columna {@code estado_cuarentena}, contrato §5.12).
 *
 * <ul>
 *   <li>{@link #PENDIENTE_REPORTE}: recién registrada; Inventory (G6) todavía no la aceptó y el outbox
 *   la reintenta.</li>
 *   <li>{@link #EN_CUARENTENA}: Inventory la aceptó; puede entrar en un acta de merma (RF-34).</li>
 *   <li>{@link #DERIVADO_ALMACEN} y {@link #BAJA_DEFINITIVA}: liquidada por un acta.</li>
 * </ul>
 *
 * <p>La <strong>cuarentena activa</strong>, la que bloquea la venta en el POS (RF-33), son
 * {@code PENDIENTE_REPORTE} y {@code EN_CUARENTENA}.</p>
 */
public enum EstadoIncidencia {
    PENDIENTE_REPORTE,
    EN_CUARENTENA,
    DERIVADO_ALMACEN,
    BAJA_DEFINITIVA;

    /** Estados que bloquean la venta del SKU en la tienda (RF-33). */
    public static final List<EstadoIncidencia> CUARENTENA_ACTIVA = List.of(PENDIENTE_REPORTE, EN_CUARENTENA);

    public boolean bloqueaVenta() {
        return CUARENTENA_ACTIVA.contains(this);
    }
}
