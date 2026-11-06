package pe.edu.unmsm.fisi.retail.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.edu.unmsm.fisi.retail.model.ValeCompra;

import java.util.Optional;
import java.util.UUID;

/** Acceso a {@code RET_VALE_COMPRA}. */
public interface ValeCompraRepository extends JpaRepository<ValeCompra, UUID> {

    Optional<ValeCompra> findBySolicitudId(UUID solicitudId);

    /** Cuantos vales tienen un prefijo dado, para numerar el siguiente del anio. */
    long countByCodigoValeStartingWith(String prefijo);
}
