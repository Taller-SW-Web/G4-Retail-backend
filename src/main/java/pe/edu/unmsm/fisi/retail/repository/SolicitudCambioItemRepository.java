package pe.edu.unmsm.fisi.retail.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.edu.unmsm.fisi.retail.model.SolicitudCambioItem;

import java.util.List;
import java.util.UUID;

/** Acceso a {@code RET_SOLICITUD_CAMBIO_ITEM}. */
public interface SolicitudCambioItemRepository extends JpaRepository<SolicitudCambioItem, UUID> {

    List<SolicitudCambioItem> findBySolicitudId(UUID solicitudId);
}
