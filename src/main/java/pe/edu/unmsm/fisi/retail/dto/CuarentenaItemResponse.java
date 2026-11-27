package pe.edu.unmsm.fisi.retail.dto;

import pe.edu.unmsm.fisi.retail.model.EstadoIncidencia;
import pe.edu.unmsm.fisi.retail.model.IncidenciaInventario;
import pe.edu.unmsm.fisi.retail.model.TipoFalla;

import java.time.OffsetDateTime;

/**
 * Elemento de {@code GET /api/v1/retail/inventario/cuarentena} (contrato §5.12, RF-33).
 */
public record CuarentenaItemResponse(
        String incidenciaId,
        String sku,
        String nombreProducto,
        TipoFalla tipoFalla,
        OffsetDateTime fechaReporte,
        EstadoIncidencia estado) {

    public static CuarentenaItemResponse de(IncidenciaInventario incidencia) {
        return new CuarentenaItemResponse(incidencia.getIdIncidencia().toString(), incidencia.getSku(),
                incidencia.getNombreProducto(), incidencia.getTipoFalla(), incidencia.getFechaReporte(),
                incidencia.getEstado());
    }
}
