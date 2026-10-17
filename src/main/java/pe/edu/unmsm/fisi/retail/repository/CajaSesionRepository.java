package pe.edu.unmsm.fisi.retail.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pe.edu.unmsm.fisi.retail.model.CajaSesion;
import pe.edu.unmsm.fisi.retail.model.EstadoCajaSesion;

import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a {@code RET_CAJA_SESION}.
 *
 * <p>El turno vigente de una terminal es, por definicion, el unico que esta {@code ABIERTA}: el
 * contrato prohibe abrir un segundo turno donde ya hay uno (responde {@code 409}). Esas dos
 * busquedas cubren todo lo que necesita F8; Spring Data genera el SQL a partir del nombre del
 * metodo.</p>
 */
@Repository
public interface CajaSesionRepository extends JpaRepository<CajaSesion, UUID> {

    /** Turno abierto de una terminal concreta. Es la consulta que decide el {@code 409}. */
    Optional<CajaSesion> findFirstByTerminalPosCodigoAndEstado(String terminalPosCodigo, EstadoCajaSesion estado);

    /**
     * Turno abierto mas reciente de una tienda.
     *
     * <p>Es la consulta de {@code GET /caja/turnos/actual}: la ruta no recibe parametros, asi que la
     * tienda se resuelve desde el token y se toma su turno vigente. Sirve para no obligar a la SPA a
     * recordar en que terminal esta parada.</p>
     */
    Optional<CajaSesion> findFirstByTiendaIdAndEstadoOrderByFechaHoraAperturaDesc(String tiendaId, EstadoCajaSesion estado);
}
