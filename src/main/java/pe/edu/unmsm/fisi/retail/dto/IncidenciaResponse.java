package pe.edu.unmsm.fisi.retail.dto;

import pe.edu.unmsm.fisi.retail.model.EstadoIncidencia;
import pe.edu.unmsm.fisi.retail.model.IncidenciaInventario;

/**
 * Respuesta {@code 201} de {@code POST /api/v1/retail/inventario/incidencias} (contrato §5.12).
 * La prenda queda bloqueada en el POS desde ese momento.
 */
public record IncidenciaResponse(String incidenciaId, EstadoIncidencia estado, String sku) {

    public static IncidenciaResponse de(IncidenciaInventario incidencia) {
        return new IncidenciaResponse(incidencia.getIdIncidencia().toString(), incidencia.getEstado(),
                incidencia.getSku());
    }
}
