package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pe.edu.unmsm.fisi.retail.dto.EntregaPickupRequest;
import pe.edu.unmsm.fisi.retail.dto.EntregaPickupResponse;
import pe.edu.unmsm.fisi.retail.dto.PedidoPickupResponse;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.integracion.ventas.PickupVentasPort;
import pe.edu.unmsm.fisi.retail.model.PerfilTienda;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas de {@link PickupService} que no dependen de HTTP: a qué sucursal pertenece la bandeja y
 * cuándo se puede entregar al titular.
 */
@ExtendWith(MockitoExtension.class)
class PickupServiceTest {

    private static final UUID VENDEDOR_A = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000a");
    private static final UUID VENDEDOR_B = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
    private static final String UUID_MIRAFLORES = "11111111-1111-1111-1111-111111111111";
    private static final String UUID_SAN_ISIDRO = "22222222-2222-2222-2222-222222222222";

    @Mock
    private PickupVentasPort ventas;

    @Mock
    private PersonalTiendaRepository personal;

    private PickupService servicio;

    @BeforeEach
    void preparar() {
        servicio = new PickupService(ventas, personal,
                UUID_MIRAFLORES + "=TIENDA-MIRAFLORES, " + UUID_SAN_ISIDRO + "=TIENDA-SAN-ISIDRO");
    }

    @Test
    @DisplayName("Dos sucursales con UUID distinto consultan cada una su propia bandeja")
    void cadaSucursalConsultaSuBandeja() {
        perfil(VENDEDOR_A, UUID_MIRAFLORES);
        perfil(VENDEDOR_B, UUID_SAN_ISIDRO);

        servicio.pendientes(VENDEDOR_A);
        servicio.pendientes(VENDEDOR_B);

        verify(ventas).listarPendientes("TIENDA-MIRAFLORES");
        verify(ventas).listarPendientes("TIENDA-SAN-ISIDRO");
    }

    @Test
    @DisplayName("Un perfil que ya guarda el código de sucursal se usa tal cual")
    void codigoTextualSeUsaTalCual() {
        perfil(VENDEDOR_A, "TIENDA-SURCO");

        servicio.pendientes(VENDEDOR_A);

        verify(ventas).listarPendientes("TIENDA-SURCO");
    }

    @Test
    @DisplayName("Un UUID sin código de sucursal configurado responde 409 y no consulta a Ventas")
    void uuidSinCodigoNoSeAdivina() {
        perfil(VENDEDOR_A, "33333333-3333-3333-3333-333333333333");

        assertThatThrownBy(() -> servicio.pendientes(VENDEDOR_A))
                .isInstanceOf(ConflictoException.class)
                .hasFieldOrPropertyWithValue("codigo", "TIENDA_SIN_CODIGO_SUCURSAL");
        verify(ventas, never()).listarPendientes(anyString());
    }

    @Test
    @DisplayName("Si el pedido no trae DNI, el titular no puede recogerlo con cualquier DNI: 422")
    void titularSinDniRegistradoResponde422() {
        perfil(VENDEDOR_A, "TIENDA-MIRAFLORES");
        when(ventas.listarPendientes("TIENDA-MIRAFLORES")).thenReturn(List.of(pedidoSinDni()));

        assertThatThrownBy(() -> servicio.entregar("PED-2026-PICKUP01", VENDEDOR_A,
                new EntregaPickupRequest("45678912", "Cliente Sin DNI", false, null, null)))
                .isInstanceOf(ReglaNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "DNI_TITULAR_NO_REGISTRADO");
        verify(ventas, never()).confirmarEntrega(anyString(), any());
    }

    @Test
    @DisplayName("Si el pedido no trae DNI, un tercero con carta poder sí puede recogerlo")
    void terceroConCartaPoderRecogePedidoSinDni() {
        perfil(VENDEDOR_A, "TIENDA-MIRAFLORES");
        when(ventas.listarPendientes("TIENDA-MIRAFLORES")).thenReturn(List.of(pedidoSinDni()));
        EntregaPickupRequest peticion = new EntregaPickupRequest("70001111", "Pedro Apoderado", true, null, true);
        when(ventas.confirmarEntrega("PED-2026-PICKUP01", peticion))
                .thenReturn(new EntregaPickupResponse("CONST-PED-2026-PICKUP01", "2026-11-07T10:00:00Z", "ENTREGADO"));

        assertThat(servicio.entregar("PED-2026-PICKUP01", VENDEDOR_A, peticion).estado()).isEqualTo("ENTREGADO");
    }

    private void perfil(UUID usuarioId, String tiendaId) {
        when(personal.findByUsuarioIdAndActivoTrue(usuarioId)).thenReturn(Optional.of(PersonalTienda.builder()
                .usuarioId(usuarioId).tiendaId(tiendaId).codigoVendedor("VEN-001")
                .perfilTienda(PerfilTienda.VENDEDOR).activo(true).build()));
    }

    private static PedidoPickupResponse pedidoSinDni() {
        return new PedidoPickupResponse("PED-2026-PICKUP01", "Cliente Sin DNI", null, "Anaquel A-01",
                "2026-11-06T10:00:00Z", "LISTO_PARA_RECOJO", List.of());
    }
}
