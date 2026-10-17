package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pe.edu.unmsm.fisi.retail.dto.PerfilResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.model.PerfilTienda;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Pruebas de la resolucion del perfil de mostrador (F1).
 *
 * <p>Criterios de aceptacion verificados: el perfil se resuelve desde el identificador del usuario
 * autenticado; quien no esta dado de alta como personal de la tienda recibe un {@code 403} y no un
 * error generico; y la respuesta tiene la forma que fija la seccion 5.1 del contrato.</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final UUID USUARIO = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID TIENDA = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private PersonalTiendaRepository personalTiendaRepository;

    @InjectMocks
    private AuthService authService;

    @Test
    @DisplayName("Devuelve el perfil del vendedor con la forma del contrato")
    void devuelveElPerfilDelVendedor() {
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(USUARIO))
                .thenReturn(Optional.of(personal("VEN-001", PerfilTienda.VENDEDOR)));

        PerfilResponse perfil = authService.obtenerPerfil(USUARIO);

        assertThat(perfil.usuarioId()).isEqualTo(USUARIO.toString());
        assertThat(perfil.codigoVendedor()).isEqualTo("VEN-001");
        assertThat(perfil.perfilTienda()).isEqualTo("VENDEDOR");
        assertThat(perfil.tiendaId()).isEqualTo(TIENDA.toString());
        assertThat(perfil.activo()).isTrue();
    }

    @Test
    @DisplayName("Distingue al supervisor, que tiene atribuciones adicionales")
    void distingueAlSupervisor() {
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(any()))
                .thenReturn(Optional.of(personal("SUP-001", PerfilTienda.SUPERVISOR)));

        assertThat(authService.obtenerPerfil(USUARIO).perfilTienda()).isEqualTo("SUPERVISOR");
    }

    @Test
    @DisplayName("Un usuario sin perfil en la tienda recibe 403, no un error generico")
    void usuarioSinPerfilRecibe403() {
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.obtenerPerfil(USUARIO))
                .isInstanceOf(AccesoDenegadoException.class)
                .hasMessageContaining("no tiene un perfil activo")
                .extracting(e -> ((AccesoDenegadoException) e).getCodigo())
                .isEqualTo("FORBIDDEN_USUARIO_SIN_TIENDA");
    }

    @Test
    @DisplayName("El personal dado de baja se trata igual que uno inexistente")
    void elPersonalInactivoNoPuedeOperar() {
        // El repositorio ya filtra por activo, de modo que una baja no devuelve fila.
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.obtenerPerfil(USUARIO))
                .isInstanceOf(AccesoDenegadoException.class);
    }

    private PersonalTienda personal(String codigo, PerfilTienda perfil) {
        return PersonalTienda.builder()
                .idPersonal(UUID.randomUUID())
                .usuarioId(USUARIO)
                .tiendaId(TIENDA)
                .codigoVendedor(codigo)
                .perfilTienda(perfil)
                .activo(true)
                .build();
    }
}
