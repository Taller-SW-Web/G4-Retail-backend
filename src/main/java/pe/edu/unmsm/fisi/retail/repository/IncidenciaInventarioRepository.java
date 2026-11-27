package pe.edu.unmsm.fisi.retail.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pe.edu.unmsm.fisi.retail.model.EstadoIncidencia;
import pe.edu.unmsm.fisi.retail.model.IncidenciaInventario;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a {@code RET_INCIDENCIA_INVENTARIO} (F12).
 *
 * <p>La cuarentena activa se consulta pasando {@link EstadoIncidencia#CUARENTENA_ACTIVA}.</p>
 */
@Repository
public interface IncidenciaInventarioRepository extends JpaRepository<IncidenciaInventario, UUID> {

    /** Reporte ya registrado con esa {@code Idempotency-Key}. */
    Optional<IncidenciaInventario> findByIdempotencyKey(String idempotencyKey);

    /** Bandeja de la tienda (RF-33): las más recientes primero. */
    List<IncidenciaInventario> findByTiendaIdAndEstadoInOrderByFechaReporteDesc(
            String tiendaId, Collection<EstadoIncidencia> estados);

    /** De los SKU dados, los que tienen alguna incidencia de la tienda en esos estados (RF-33). */
    @Query("""
            select distinct i.sku from IncidenciaInventario i
             where i.tiendaId = :tiendaId and i.estado in :estados and i.sku in :skus
            """)
    List<String> skusConEstado(@Param("tiendaId") String tiendaId,
                               @Param("skus") Collection<String> skus,
                               @Param("estados") Collection<EstadoIncidencia> estados);
}
