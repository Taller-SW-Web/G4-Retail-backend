package pe.edu.unmsm.fisi.retail.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import pe.edu.unmsm.fisi.retail.model.ValeCompra;

import java.util.Optional;
import java.util.UUID;

/** Acceso a {@code RET_VALE_COMPRA}. */
public interface ValeCompraRepository extends JpaRepository<ValeCompra, UUID> {

    Optional<ValeCompra> findBySolicitudId(UUID solicitudId);

    @Query(value = "SELECT nextval('ret_vale_codigo_seq')", nativeQuery = true)
    long siguienteCorrelativo();
}
