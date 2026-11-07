package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pe.edu.unmsm.fisi.retail.dto.AperturaTurnoRequest;
import pe.edu.unmsm.fisi.retail.dto.CierreTurnoRequest;
import pe.edu.unmsm.fisi.retail.dto.ReporteZResponse;
import pe.edu.unmsm.fisi.retail.dto.TurnoCajaResponse;
import pe.edu.unmsm.fisi.retail.dto.VentaRequest;
import pe.edu.unmsm.fisi.retail.dto.VentaResponse;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Flujo Integrado: Venta en mostrador -> Cobro asociado -> Corte Z de Caja")
class VentaCobroCorteZIntegrationTest {

    @Mock
    private CajaSesionRepository cajaSesionRepository;

    @Mock
    private CajaMovimientoRepository cajaMovimientoRepository;

    @Mock
    private CobroTurnoRepository cobroTurnoRepository;

    @Mock
    private PersonalTiendaRepository personalTiendaRepository;

    @Test
    @DisplayName("Una venta en efectivo se cobra en el turno y computa en el arqueo del Corte Z")
    void ventaEnEfectivoComputaEnCorteZ() {
        UUID usuarioId = UUID.randomUUID();
        UUID turnoId = UUID.randomUUID();
        String tiendaId = "TIENDA-MIRAFLORES";

        PersonalTienda personal = PersonalTienda.builder()
                .usuarioId(usuarioId)
                .tiendaId(tiendaId)
                .codigoVendedor("VEN-001")
                .activo(true)
                .build();

        CajaSesion sesion = CajaSesion.builder()
                .idSesion(turnoId)
                .tiendaId(tiendaId)
                .terminalPosCodigo("POS-01")
                .vendedorId(usuarioId)
                .fechaHoraApertura(OffsetDateTime.now())
                .saldoInicialEfectivo(new BigDecimal("200.00"))
                .estado(EstadoCajaSesion.ABIERTA)
                .build();

        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(usuarioId)).thenReturn(Optional.of(personal));
        when(cajaSesionRepository.findById(turnoId)).thenReturn(Optional.of(sesion));
        when(cobroTurnoRepository.findByIdempotencyKey("idemp-venta-1")).thenReturn(Optional.empty());

        CajaService cajaService = new CajaService(cajaSesionRepository, cajaMovimientoRepository, personalTiendaRepository, cobroTurnoRepository);
        VentaService ventaService = new VentaService(null, cajaService);

        VentaRequest.ItemVentaDto item = new VentaRequest.ItemVentaDto("SKU-POLO", 1, new BigDecimal("100.00"));
        VentaRequest.PagoDto pago = new VentaRequest.PagoDto("EFECTIVO", new BigDecimal("100.00"), null, null);
        VentaRequest.ComprobanteDto comprobante = new VentaRequest.ComprobanteDto("BOLETA", "12345678", "Juan Perez");

        VentaRequest request = new VentaRequest("POS-01", turnoId.toString(), "cliente-1",
                List.of(item), pago, comprobante, false, null, null);

        // 1. Ejecutar venta
        VentaResponse respuestaVenta = ventaService.registrarVenta(request, "idemp-venta-1");
        assertThat(respuestaVenta).isNotNull();
        assertThat(respuestaVenta.pago().medioPago()).isEqualTo("EFECTIVO");
        assertThat(respuestaVenta.comprobante().total()).isEqualByComparingTo("100.00");

        // Verificar que el cobro se guardo
        verify(cobroTurnoRepository).save(any(CobroTurno.class));

        // 2. Simular que el total en efectivo vigente del turno suma S/ 100.00
        when(cobroTurnoRepository.totalEfectivoVigente(eq(turnoId), eq(EstadoCobroTurno.ANULADO)))
                .thenReturn(new BigDecimal("100.00"));

        // 3. Ejecutar Corte Z (Cierre de turno)
        // Fondo inicial: 200.00 + Ventas efectivo: 100.00 = Saldo esperado: 300.00
        ReporteZResponse reporteZ = cajaService.cerrar(usuarioId, turnoId, new CierreTurnoRequest(new BigDecimal("300.00"), "Cierre conforme"));

        assertThat(reporteZ).isNotNull();
        assertThat(reporteZ.saldoInicial()).isEqualByComparingTo("200.00");
        assertThat(reporteZ.ventasEfectivoTotal()).isEqualByComparingTo("100.00");
        assertThat(reporteZ.saldoSistema()).isEqualByComparingTo("300.00");
        assertThat(reporteZ.saldoDeclarado()).isEqualByComparingTo("300.00");
        assertThat(reporteZ.diferencia()).isEqualByComparingTo("0.00");
        assertThat(reporteZ.estado()).isEqualTo("CERRADA");
    }
}
