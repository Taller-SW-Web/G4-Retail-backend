package pe.edu.unmsm.fisi.retail.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.edu.unmsm.fisi.retail.model.EstadoAprobacionCambio;
import pe.edu.unmsm.fisi.retail.model.SolicitudCambio;

import java.util.Optional;
import java.util.UUID;

/** Acceso a {@code RET_SOLICITUD_CAMBIO_MOSTRADOR}. */
public interface SolicitudCambioRepository extends JpaRepository<SolicitudCambio, UUID> {

    Optional<SolicitudCambio> findByIdempotencyKey(String idempotencyKey);

    /** Unidades de un SKU que ya se cambiaron con esta boleta: no se puede cambiar dos veces la misma prenda. */
    @Query("""
            select coalesce(sum(s.cantidad), 0) from SolicitudCambio s
            where s.boletaNumero = :boleta and s.varianteSku = :sku and s.estadoAprobacion = :estado
            """)
    long unidadesYaCambiadas(@Param("boleta") String boletaNumero,
                             @Param("sku") String sku,
                             @Param("estado") EstadoAprobacionCambio estado);
}
