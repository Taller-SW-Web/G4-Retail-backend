package pe.edu.unmsm.fisi.retail.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pe.edu.unmsm.fisi.retail.model.CobroTurno;
import pe.edu.unmsm.fisi.retail.model.EstadoCobroTurno;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a {@code RET_COBRO_TURNO}.
 *
 * <p>Igual que en {@code CajaMovimientoRepository}, la suma se pide a la base y no se hace en
 * memoria, para que el efectivo que decide un egreso sea el mismo que se muestra en el Reporte Z.</p>
 */
@Repository
public interface CobroTurnoRepository extends JpaRepository<CobroTurno, UUID> {

    /** Cobro ya registrado con esa llave de idempotencia, si existe. */
    Optional<CobroTurno> findByIdempotencyKey(String idempotencyKey);

    /**
     * Efectivo cobrado en el turno: suma de {@code monto_total} de los cobros en efectivo cuyo
     * estado sea distinto de {@code excluido} (se pasa {@code ANULADO}).
     *
     * <p>Devuelve {@code null} si no hay cobros; el servicio lo traduce a cero.</p>
     */
    @Query("""
            select sum(c.montoTotal)
            from CobroTurno c
            where c.cajaSesionId = :cajaSesionId
              and c.medioPago = 'EFECTIVO'
              and c.estado <> :excluido
            """)
    BigDecimal totalEfectivoVigente(@Param("cajaSesionId") UUID cajaSesionId,
                                    @Param("excluido") EstadoCobroTurno excluido);
}
