package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import pe.edu.unmsm.fisi.retail.dto.VentaRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.integracion.ventas.RegistroPedidosMostrador;
import pe.edu.unmsm.fisi.retail.model.CajaSesion;
import pe.edu.unmsm.fisi.retail.model.CobroTurno;
import pe.edu.unmsm.fisi.retail.model.EstadoCajaSesion;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.repository.CajaMovimientoRepository;
import pe.edu.unmsm.fisi.retail.repository.CajaSesionRepository;
import pe.edu.unmsm.fisi.retail.repository.CobroTurnoRepository;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Enlace de {@code VentaService} con el cobro del turno (F8): la identidad del vendedor y los fallos
 * al persistir el cobro.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VentaService: cobro de la venta en el turno de caja")
class VentaServiceCobroTest {

    private static final UUID USUARIO = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID TURNO = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final String TIENDA = "TIENDA-MIRAFLORES";
    private static final String TERMINAL = "POS-01";

    @Mock private CajaSesionRepository cajaSesionRepository;
    @Mock private CajaMovimientoRepository cajaMovimientoRepository;
    @Mock private CobroTurnoRepository cobroTurnoRepository;
    @Mock private PersonalTiendaRepository personalTiendaRepository;

    private VentaService ventaService() {
        return new VentaService(null, new CajaService(cajaSesionRepository, cajaMovimientoRepository,
                personalTiendaRepository, cobroTurnoRepository));
    }

    @Test
    @DisplayName("Negativo: si el usuario es el propio turnoId, se valida su perfil y responde 403 sin cobrar")
    void turnoComoUsuarioNoSeSaltaLaValidacion() {
        VentaService servicio = ventaService();

        assertThatThrownBy(() -> servicio.registrarVenta(requestEfectivo(), "idemp-1", TURNO))
                .isInstanceOf(AccesoDenegadoException.class)
                .hasFieldOrPropertyWithValue("codigo", "FORBIDDEN_USUARIO_SIN_TIENDA");

        verify(personalTiendaRepository).findByUsuarioIdAndActivoTrue(TURNO);
        verify(cobroTurnoRepository, never()).save(any());
    }

    @Test
    @DisplayName("Negativo: si falla guardar el cobro, la venta no queda PAGADO ni en caché y el reintento cobra")
    void falloAlPersistirElCobroSePropagaYElReintentoCobra() {
        turnoAbiertoDelVendedor();
        when(cobroTurnoRepository.findByIdempotencyKey("idemp-1")).thenReturn(Optional.empty());
        when(cobroTurnoRepository.save(any(CobroTurno.class)))
                .thenThrow(new DataAccessResourceFailureException("conexion perdida"))
                .thenAnswer(inv -> inv.getArgument(0));
        RegistroPedidosMostrador registro = mock(RegistroPedidosMostrador.class);
        VentaService servicio = new VentaService(null, new CajaService(cajaSesionRepository,
                cajaMovimientoRepository, personalTiendaRepository, cobroTurnoRepository), registro);

        assertThatThrownBy(() -> servicio.registrarVenta(requestEfectivo(), "idemp-1", USUARIO))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(registro, never()).registrar(any(), anyString());

        // La llave no quedó cacheada: el reintento vuelve a intentar el cobro y ahora sí lo guarda
        VentaResponse respuesta = servicio.registrarVenta(requestEfectivo(), "idemp-1", USUARIO);

        assertThat(respuesta.estadoPedido()).isEqualTo("PAGADO");
        verify(cobroTurnoRepository, times(2)).save(any(CobroTurno.class));
        verify(registro).registrar(any(), anyString());
    }

    @Test
    @DisplayName("Se valida el perfil del usuario autenticado, no el del turno")
    void validaElPerfilDelUsuarioAutenticado() {
        turnoAbiertoDelVendedor();
        when(cobroTurnoRepository.findByIdempotencyKey("idemp-1")).thenReturn(Optional.empty());

        ventaService().registrarVenta(requestEfectivo(), "idemp-1", USUARIO);

        verify(personalTiendaRepository, times(2)).findByUsuarioIdAndActivoTrue(USUARIO);
        verify(personalTiendaRepository, never()).findByUsuarioIdAndActivoTrue(TURNO);
        verify(cobroTurnoRepository).save(any(CobroTurno.class));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void turnoAbiertoDelVendedor() {
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(USUARIO))
                .thenReturn(Optional.of(PersonalTienda.builder()
                        .usuarioId(USUARIO).tiendaId(TIENDA).codigoVendedor("VEN-001").activo(true).build()));
        when(cajaSesionRepository.findById(TURNO))
                .thenReturn(Optional.of(CajaSesion.builder()
                        .idSesion(TURNO)
                        .tiendaId(TIENDA)
                        .terminalPosCodigo(TERMINAL)
                        .vendedorId(USUARIO)
                        .fechaHoraApertura(OffsetDateTime.now())
                        .saldoInicialEfectivo(new BigDecimal("200.00"))
                        .estado(EstadoCajaSesion.ABIERTA)
                        .build()));
    }

    private static VentaRequest requestEfectivo() {
        return new VentaRequest(TERMINAL, TURNO.toString(), null,
                List.of(new VentaRequest.ItemVentaDto("SKU-POLO", 1, new BigDecimal("100.00"))),
                new VentaRequest.PagoDto("EFECTIVO", new BigDecimal("100.00"), null, null),
                new VentaRequest.ComprobanteDto("BOLETA", null, null),
                false, null, null);
    }
}
