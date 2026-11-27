package pe.edu.unmsm.fisi.retail.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Lo que el acta de merma (RF-34) necesita de {@code RET_INCIDENCIA_INVENTARIO}: leer las incidencias que
 * se liquidan y cerrarlas con el acta.
 *
 * <p>Va por SQL y no por una entidad JPA: la entidad de la incidencia es de F12 (Angie), y una segunda
 * entidad sobre la misma tabla chocaria con la suya. Las columnas son las de
 * {@code diseño/schema-s14-f12.sql}.</p>
 */
@Repository
@RequiredArgsConstructor
public class IncidenciasActaRepository {

    private final JdbcClient jdbc;

    /** Incidencia tal como entra al acta. */
    public record Incidencia(UUID id, String sku, String tipoFalla, String detalleObservacion, String estado) {}

    /**
     * Incidencias de la tienda con esos ids, bloqueadas hasta el fin de la transaccion: dos actas a la vez
     * no pueden liquidar la misma prenda.
     */
    public List<Incidencia> bloquear(Collection<UUID> ids, String tiendaId) {
        return jdbc.sql("""
                SELECT id_incidencia, variante_sku_id, tipo_falla, detalle_observacion, estado_cuarentena
                FROM RET_INCIDENCIA_INVENTARIO
                WHERE tienda_id = :tienda AND id_incidencia IN (:ids)
                FOR UPDATE
                """)
                .param("tienda", tiendaId)
                .param("ids", ids)
                .query((fila, i) -> new Incidencia(fila.getObject("id_incidencia", UUID.class),
                        fila.getString("variante_sku_id"), fila.getString("tipo_falla"),
                        fila.getString("detalle_observacion"), fila.getString("estado_cuarentena")))
                .list();
    }

    /** Cierra las incidencias con el acta: nuevo estado y el acta que las despacho. */
    public void liquidar(Collection<UUID> ids, UUID actaId, String estado) {
        jdbc.sql("""
                UPDATE RET_INCIDENCIA_INVENTARIO SET estado_cuarentena = :estado, acta_id = :acta
                WHERE id_incidencia IN (:ids)
                """)
                .param("estado", estado)
                .param("acta", actaId)
                .param("ids", ids)
                .update();
    }
}
