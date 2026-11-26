package pe.edu.unmsm.fisi.retail.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.edu.unmsm.fisi.retail.model.EstadoOutbox;
import pe.edu.unmsm.fisi.retail.model.OutboxEvento;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Acceso a {@code RET_OUTBOX_EVENTO} (contrato v0.5, §2.9). */
public interface OutboxEventoRepository extends JpaRepository<OutboxEvento, UUID> {

    /** Eventos pendientes cuyo próximo intento ya llegó, del más antiguo al más nuevo. */
    @Query("""
            select e from OutboxEvento e
            where e.estado = :estado and e.proximoIntento <= :ahora
            order by e.creadoEn asc
            """)
    List<OutboxEvento> listosParaEnviar(@Param("estado") EstadoOutbox estado,
                                        @Param("ahora") OffsetDateTime ahora,
                                        Pageable lote);

    Optional<OutboxEvento> findByTipoEventoAndAgregadoId(String tipoEvento, String agregadoId);

    long countByEstado(EstadoOutbox estado);
}
