package pe.edu.unmsm.fisi.retail.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import pe.edu.unmsm.fisi.retail.config.LocalDatabaseConfig;
import pe.edu.unmsm.fisi.retail.model.PerfilTienda;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas del mapeo de {@code RET_PERSONAL_TIENDA}.
 *
 * <p>Criterio de aceptacion de la spec F1 que se verifica: el perfil operativo se resuelve a
 * partir del identificador del usuario (el {@code sub} del JWT) y <strong>solo</strong> si el
 * registro esta activo; un perfil inactivo no debe poder operar en mostrador.</p>
 */
@DataJpaTest
@ActiveProfiles({"local", "mock"})
@Import(LocalDatabaseConfig.class)
class PersonalTiendaRepositoryTest {

    private static final UUID TIENDA_MIRAFLORES = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private PersonalTiendaRepository repositorio;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("Encuentra el perfil activo de un vendedor por su usuarioId")
    void encuentraPerfilActivo() {
        UUID usuarioId = UUID.randomUUID();
        entityManager.persistAndFlush(personal(usuarioId, "VEN-001", PerfilTienda.VENDEDOR, true));

        Optional<PersonalTienda> encontrado = repositorio.findByUsuarioIdAndActivoTrue(usuarioId);

        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().getCodigoVendedor()).isEqualTo("VEN-001");
        assertThat(encontrado.get().getPerfilTienda()).isEqualTo(PerfilTienda.VENDEDOR);
        assertThat(encontrado.get().getTiendaId()).isEqualTo(TIENDA_MIRAFLORES);
        assertThat(encontrado.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("No devuelve al personal dado de baja, aunque exista la fila")
    void ignoraPerfilInactivo() {
        UUID usuarioId = UUID.randomUUID();
        entityManager.persistAndFlush(personal(usuarioId, "VEN-002", PerfilTienda.VENDEDOR, false));

        assertThat(repositorio.findByUsuarioIdAndActivoTrue(usuarioId)).isEmpty();
    }

    @Test
    @DisplayName("Distingue la tienda: el mismo usuario no opera en una tienda ajena")
    void distingueLaTienda() {
        UUID usuarioId = UUID.randomUUID();
        entityManager.persistAndFlush(personal(usuarioId, "SUP-001", PerfilTienda.SUPERVISOR, true));

        assertThat(repositorio.findByUsuarioIdAndTiendaIdAndActivoTrue(usuarioId, TIENDA_MIRAFLORES)).isPresent();
        assertThat(repositorio.findByUsuarioIdAndTiendaIdAndActivoTrue(usuarioId, UUID.randomUUID())).isEmpty();
    }

    private PersonalTienda personal(UUID usuarioId, String codigo, PerfilTienda perfil, boolean activo) {
        return PersonalTienda.builder()
                .usuarioId(usuarioId)
                .tiendaId(TIENDA_MIRAFLORES)
                .codigoVendedor(codigo)
                .perfilTienda(perfil)
                .activo(activo)
                .build();
    }
}
