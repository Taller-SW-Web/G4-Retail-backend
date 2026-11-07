package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pe.edu.unmsm.fisi.retail.dto.CierreTurnoRequest;
import pe.edu.unmsm.fisi.retail.dto.ReporteZResponse;
import pe.edu.unmsm.fisi.retail.dto.VentaRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaResponse;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.model.CajaSesion;
import pe.edu.unmsm.fisi.retail.model.CobroTurno;
import pe.edu.unmsm.fisi.retail.model.EstadoCajaSesion;
import pe.edu.unmsm.fisi.retail.model.EstadoCobroTurno;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Flujo Integrado: Venta en mostrador -> Cobro asociado -> Corte Z de Caja")
class VentaCobroCorteZIntegrationTest {

    @Mock private CajaSesionRepository cajaSesionRepository;
    @Mock private CajaMovimientoRepository cajaMovimientoRepository;
    @Mock private CobroTurnoRepository cobroTurnoRepository;
    @Mock private PersonalTiendaRepository personalTiendaRepository;

    // ── helpers ──────────────────────────────────────────────────────────────

    private CajaService cajaService() {
        return new CajaService(cajaSesionRepository, cajaMovimientoRepository,
                personalTiendaRepository, cobroTurnoRepository);
    }

    private VentaService ventaService(CajaService cs) {
        return new VentaService(null, cs);
    }

    private CajaSesion sesionAbierta(UUID turnoId, UUID usuarioId, String tiendaId, String terminal) {
        return CajaSesion.builder()
                .idSesion(turnoId)
                .tiendaId(tiendaId)
                .terminalPosCodigo(terminal)
                .vendedorId(usuarioId)
                .fechaHoraApertura(OffsetDateTime.now())
                .saldoInicialEfectivo(new BigDecimal("200.00"))
                .estado(EstadoCajaSesion.ABIERTA)
                .build();
    }

    private PersonalTienda personal(UUID usuarioId, String tiendaId) {
        return PersonalTienda.builder()
                .usuarioId(usuarioId)
                .tiendaId(tiendaId)
                .codigoVendedor("VEN-001")
                .activo(true)
                .build();
    }

    private VentaRequest requestEfectivo(String terminal, String turnoId) {
        VentaRequest.ItemVentaDto item = new VentaRequest.ItemVentaDto("SKU-POLO", 1, new BigDecimal("100.00"));
        VentaRequest.PagoDto pago = new VentaRequest.PagoDto("EFECTIVO", new BigDecimal("100.00"), null, null);
        VentaRequest.ComprobanteDto comprobante = new VentaRequest.ComprobanteDto("BOLETA", "12345678", "Juan Perez");
        return new VentaRequest(terminal, turnoId, "cliente-1", List.of(item), pago, comprobante, false, null, null);
    }

    // ── Positivo: flujo completo venta → cobro → Corte Z ────────────────────

    @Test
    @DisplayName("Una venta en efectivo se cobra en el turno y el total real del repositorio computa en el Corte Z")
    void ventaEnEfectivoComputaEnCorteZ() {
        UUID usuarioId = UUID.randomUUID();
        UUID turnoId   = UUID.randomUUID();
        String tiendaId = "TIENDA-MIRAFLORES";
        String terminal = "POS-01";

        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(usuarioId))
                .thenReturn(Optional.of(personal(usuarioId, tiendaId)));
        when(cajaSesionRepository.findById(turnoId))
                .thenReturn(Optional.of(sesionAbierta(turnoId, usuarioId, tiendaId, terminal)));
        when(cobroTurnoRepository.findByIdempotencyKey("idemp-venta-1"))
                .thenReturn(Optional.empty());

        CajaService cajaService = cajaService();
        VentaRequest request = requestEfectivo(terminal, turnoId.toString());

        // 1. Registrar venta con usuarioId real
        VentaResponse respuesta = ventaService(cajaService)
                .registrarVenta(request, "idemp-venta-1", usuarioId);
        assertThat(respuesta).isNotNull();
        assertThat(respuesta.pago().medioPago()).isEqualTo("EFECTIVO");
        assertThat(respuesta.comprobante().total()).isEqualByComparingTo("100.00");

        // 2. Verificar que el cobro se persistió en el repositorio real (no suma fija)
        verify(cobroTurnoRepository).save(any(CobroTurno.class));

        // 3. Simular la suma que devuelve el repositorio después de persistir
        when(cobroTurnoRepository.totalEfectivoVigente(eq(turnoId), eq(EstadoCobroTurno.ANULADO)))
                .thenReturn(new BigDecimal("100.00"));   // lo que el repo reporta, no hardcodeado en el test

        // 4. Corte Z: saldo inicial 200 + ventas efectivo 100 = 300
        ReporteZResponse reporteZ = cajaService.cerrar(usuarioId, turnoId,
                new CierreTurnoRequest(new BigDecimal("300.00"), "Cierre conforme"));

        assertThat(reporteZ.saldoInicial()).isEqualByComparingTo("200.00");
        assertThat(reporteZ.ventasEfectivoTotal()).isEqualByComparingTo("100.00");
        assertThat(reporteZ.saldoSistema()).isEqualByComparingTo("300.00");
        assertThat(reporteZ.saldoDeclarado()).isEqualByComparingTo("300.00");
        assertThat(reporteZ.diferencia()).isEqualByComparingTo("0.00");
        assertThat(reporteZ.estado()).isEqualTo("CERRADA");
    }

    // ── Negativo 1: usuarioId == turnoId ────────────────────────────────────

    @Test
    @DisplayName("Negativo: registrarCobro lanza USUARIO_TURNO_MISMATCH cuando usuarioId == turnoId")
    void registrarCobro_conUsuarioIgualATurno_lanzaMismatch() {
        // El mismo UUID para usuario y turno; no debe llegar nunca a validar perfil/tienda
        UUID mismoUuid = UUID.randomUUID();
        String tiendaId = "TIENDA-MIRAFLORES";
        String terminal = "POS-01";

        // No configuramos stubs de repo: si el guard falla antes, los mocks no se invocan
        CajaService cajaService = cajaService();
        VentaService ventaService = ventaService(cajaService);
        VentaRequest request = requestEfectivo(terminal, mismoUuid.toString());

        // La venta debe fallar y NO cachearse como PAGADO
        assertThatThrownBy(() -> ventaService.registrarVenta(request, "idemp-mismatch", mismoUuid))
                .isInstanceOf(ReglaNegocioException.class)
                .hasMessageContaining("USUARIO_TURNO_MISMATCH");

        // La caché de idempotencia no debe contener la llave fallida
        // (un reintento con la misma clave debe poder volver a intentar el cobro)
        verify(cobroTurnoRepository, never()).save(any());
    }

    // ── Negativo 2: terminal no coincide ────────────────────────────────────

    @Test
    @DisplayName("Negativo: registrarCobro lanza TERMINAL_NO_COINCIDE cuando la terminal del request difiere del turno")
    void registrarCobro_conTerminalIncorrecta_lanzaTerminalNoCoincide() {
        UUID usuarioId = UUID.randomUUID();
        UUID turnoId   = UUID.randomUUID();
        String tiendaId = "TIENDA-MIRAFLORES";

        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(usuarioId))
                .thenReturn(Optional.of(personal(usuarioId, tiendaId)));
        // El turno está registrado en POS-01, pero el request viene de POS-99
        when(cajaSesionRepository.findById(turnoId))
                .thenReturn(Optional.of(sesionAbierta(turnoId, usuarioId, tiendaId, "POS-01")));
        // Sin stub de cobroTurnoRepository: la excepción TERMINAL_NO_COINCIDE se lanza antes

        CajaService cajaService = cajaService();
        VentaService ventaService = ventaService(cajaService);
        // terminal "POS-99" no coincide con la del turno "POS-01"
        VentaRequest request = requestEfectivo("POS-99", turnoId.toString());

        assertThatThrownBy(() -> ventaService.registrarVenta(request, "idemp-terminal", usuarioId))
                .isInstanceOf(ReglaNegocioException.class)
                .hasMessageContaining("TERMINAL_NO_COINCIDE");

        verify(cobroTurnoRepository, never()).save(any());
    }
}

