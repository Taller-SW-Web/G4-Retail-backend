package pe.edu.unmsm.fisi.retail.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pe.edu.unmsm.fisi.retail.model.CajaMovimiento;
import pe.edu.unmsm.fisi.retail.model.TipoMovimientoCaja;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Acceso a {@code RET_MOVIMIENTO_CAJA}.
 *
 * <p>El saldo estimado en gaveta se arma sumando los movimientos del turno por tipo. La suma se
 * pide a la base en lugar de traer todas las filas y sumarlas en memoria, para que el numero que
 * decide si un egreso es posible (regla del {@code 422}) se calcule sobre el mismo dato que luego
 * se muestra en el Reporte Z.</p>
 */
@Repository
public interface CajaMovimientoRepository extends JpaRepository<CajaMovimiento, UUID> {

    /** Movimientos del turno, del mas antiguo al mas reciente, para la bitacora de la pantalla. */
    List<CajaMovimiento> findByCajaSesionIdOrderByFechaHoraAsc(UUID cajaSesionId);

    /**
     * Total de un tipo de movimiento dentro del turno.
     *
     * <p>Devuelve {@code null} cuando todavia no hay movimientos; el servicio lo traduce a cero con
     * {@code nullSeguro}. Se deja que la base devuelva el nulo en lugar de forzar un
     * {@code coalesce} para que la consulta sea identica en H2 (perfil {@code local}) y en
     * PostgreSQL (Supabase).</p>
     */
    @Query("""
            select sum(m.monto)
            from CajaMovimiento m
            where m.cajaSesionId = :cajaSesionId and m.tipoMovimiento = :tipo
            """)
    BigDecimal totalPorTipo(@Param("cajaSesionId") UUID cajaSesionId,
                            @Param("tipo") TipoMovimientoCaja tipo);
}
