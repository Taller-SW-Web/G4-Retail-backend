package pe.edu.unmsm.fisi.retail.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;

import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a {@code RET_PERSONAL_TIENDA}.
 *
 * <p>Spring Data genera la consulta a partir del nombre del metodo, de modo que no hace falta
 * escribir SQL para estas busquedas.</p>
 */
@Repository
public interface PersonalTiendaRepository extends JpaRepository<PersonalTienda, UUID> {

    /** Perfil activo del usuario autenticado, buscado por el {@code sub} del JWT. */
    Optional<PersonalTienda> findByUsuarioIdAndActivoTrue(UUID usuarioId);

    /** Perfil activo del usuario en una tienda concreta. */
    Optional<PersonalTienda> findByUsuarioIdAndTiendaIdAndActivoTrue(UUID usuarioId, UUID tiendaId);

    /** Comprueba si ya existe un registro de personal para el usuarioId indicado. */
    boolean existsByUsuarioId(UUID usuarioId);
}
