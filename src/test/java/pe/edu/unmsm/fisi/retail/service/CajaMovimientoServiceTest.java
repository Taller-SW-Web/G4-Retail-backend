package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pe.edu.unmsm.fisi.retail.dto.MovimientoTurnoRequest;
import pe.edu.unmsm.fisi.retail.dto.MovimientoTurnoResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.model.CajaMovimiento;
import pe.edu.unmsm.fisi.retail.model.CajaSesion;
import pe.edu.unmsm.fisi.retail.model.EstadoCajaSesion;
import pe.edu.unmsm.fisi.retail.model.PerfilTienda;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.model.TipoMovimientoCaja;
import pe.edu.unmsm.fisi.retail.repository.CajaMovimientoRepository;
import pe.edu.unmsm.fisi.retail.repository.CajaSesionRepository;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Movimientos menores de efectivo del turno (F8, RF-21).
 *
 * <p>Complementa a {@link CajaServiceTest} con el foco en {@code registrarMovimiento}: el ingreso
 * suma al saldo estimado de la gaveta, el egreso resta, un egreso nunca supera el efectivo
 * disponible (pero puede vaciar la gaveta exactamente), los montos se guardan con dos decimales y
 * un turno ajeno o cerrado no admite movimientos.</p>
 */
@ExtendWith(MockitoExtension.class)
class CajaMovimientoServiceTest {

    private static final UUID USUARIO = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID TIENDA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTRA_TIENDA = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TURNO = UUID.fromString("cccccccc-0000-0000-0001-000000000001");
    private static final String TERMINAL = "POS-01";
    private static final String FONDO_FIJO = "200.00";

    @Mock
    private CajaSesionRepository cajaSesionRepository;

    @Mock
    private CajaMovimientoRepository cajaMovimientoRepository;

    @Mock
    private PersonalTiendaRepository personalTiendaRepository;

    @InjectMocks
    private CajaService cajaService;

    @Test
    @DisplayName("Un ingreso menor suma al saldo estimado de la gaveta")
    void ingresoSumaAlSaldo() {
        stubTurnoAbierto(turnoAbierto());
        stubTotales("0.00", "0.00");
        stubGuardado();

        MovimientoTurnoResponse respuesta = cajaService.registrarMovimiento(USUARIO, TURNO,
                new MovimientoTurnoRequest(TipoMovimientoCaja.INGRESO_MENOR, new BigDecimal("30.00"),
                        "Cambio de sencillo", "SUP-001"));

        // Los totales del mock no incluyen el movimiento recien guardado; el saldo se recalcula
        // con lo que haya en base, asi que aqui se verifica lo que se persistio.
        CajaMovimiento guardado = capturarGuardado();
        assertThat(guardado.getTipoMovimiento()).isEqualTo(TipoMovimientoCaja.INGRESO_MENOR);
        assertThat(guardado.getMonto()).isEqualByComparingTo("30.00");
        assertThat(respuesta).isNotNull();
    }

    @Test
    @DisplayName("El saldo estimado refleja ingresos previos y egresos previos del turno")
    void saldoEstimadoConsideraLosMovimientosDelTurno() {
        stubTurnoAbierto(turnoAbierto());
        // 200.00 fondo + 80.00 ingresos - 30.00 egresos = 250.00
        stubTotales("80.00", "30.00");
        stubGuardado();

        MovimientoTurnoResponse respuesta = cajaService.registrarMovimiento(USUARIO, TURNO,
                new MovimientoTurnoRequest(TipoMovimientoCaja.SALIDA_GASTO, new BigDecimal("20.00"),
                        "Compra de bolsas", "SUP-001"));

        assertThat(respuesta.nuevoSaldoEstimado()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("Un egreso puede vaciar la gaveta exactamente")
    void egresoIgualAlSaldoEsValido() {
        stubTurnoAbierto(turnoAbierto());
        stubTotales("0.00", "0.00");
        stubGuardado();

        cajaService.registrarMovimiento(USUARIO, TURNO,
                new MovimientoTurnoRequest(TipoMovimientoCaja.SALIDA_GASTO, new BigDecimal(FONDO_FIJO),
                        "Deposito a boveda", "SUP-001"));

        assertThat(capturarGuardado().getMonto()).isEqualByComparingTo(FONDO_FIJO);
    }

    @Test
    @DisplayName("Un egreso de un centimo sobre el saldo responde 422 y no se guarda")
    void egresoUnCentimoSobreElSaldoSeRechaza() {
        stubTurnoAbierto(turnoAbierto());
        stubTotales("0.00", "0.00");

        assertThatThrownBy(() -> cajaService.registrarMovimiento(USUARIO, TURNO,
                new MovimientoTurnoRequest(TipoMovimientoCaja.SALIDA_GASTO, new BigDecimal("200.01"),
                        "Gasto", "SUP-001")))
                .isInstanceOf(ReglaNegocioException.class)
                .extracting(excepcion -> ((ReglaNegocioException) excepcion).getCodigo())
                .isEqualTo("FONDOS_INSUFICIENTES_EN_GAVETA");

        verify(cajaMovimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("Un ingreso no se valida contra el efectivo disponible")
    void ingresoNoDependeDelSaldo() {
        stubTurnoAbierto(turnoAbierto());
        stubTotales("0.00", "200.00"); // gaveta en cero
        stubGuardado();

        cajaService.registrarMovimiento(USUARIO, TURNO,
                new MovimientoTurnoRequest(TipoMovimientoCaja.INGRESO_MENOR, new BigDecimal("500.00"),
                        "Reposicion de sencillo", "SUP-001"));

        assertThat(capturarGuardado().getMonto()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("El monto se guarda con dos decimales y la autorizacion queda registrada")
    void montoSeEscalaADosDecimales() {
        stubTurnoAbierto(turnoAbierto());
        stubTotales("0.00", "0.00");
        stubGuardado();

        cajaService.registrarMovimiento(USUARIO, TURNO,
                new MovimientoTurnoRequest(TipoMovimientoCaja.INGRESO_MENOR, new BigDecimal("10.005"),
                        "Propina", "Supervisor Juan"));

        CajaMovimiento guardado = capturarGuardado();
        assertThat(guardado.getMonto().scale()).isEqualTo(2);
        assertThat(guardado.getMonto()).isEqualByComparingTo("10.01");
        assertThat(guardado.getAutorizadoPorSupervisor()).isEqualTo("Supervisor Juan");
        assertThat(guardado.getMotivo()).isEqualTo("Propina");
        assertThat(guardado.getCajaSesionId()).isEqualTo(TURNO);
    }

    @Test
    @DisplayName("Registrar un movimiento en un turno cerrado responde 409 y no se guarda")
    void turnoCerradoNoAdmiteMovimientos() {
        CajaSesion cerrada = turnoAbierto();
        cerrada.setEstado(EstadoCajaSesion.CERRADA);
        stubTurnoAbierto(cerrada);

        assertThatThrownBy(() -> cajaService.registrarMovimiento(USUARIO, TURNO,
                new MovimientoTurnoRequest(TipoMovimientoCaja.INGRESO_MENOR, BigDecimal.TEN, "Sencillo", "SUP-001")))
                .isInstanceOf(ConflictoException.class)
                .extracting(excepcion -> ((ConflictoException) excepcion).getCodigo())
                .isEqualTo("TURNO_YA_CERRADO");

        verify(cajaMovimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("Registrar un movimiento en el turno de otra tienda responde 403 y no se guarda")
    void turnoDeOtraTiendaNoAdmiteMovimientos() {
        CajaSesion ajena = turnoAbierto();
        ajena.setTiendaId(OTRA_TIENDA.toString());
        stubTurnoAbierto(ajena);

        assertThatThrownBy(() -> cajaService.registrarMovimiento(USUARIO, TURNO,
                new MovimientoTurnoRequest(TipoMovimientoCaja.INGRESO_MENOR, BigDecimal.TEN, "Sencillo", "SUP-001")))
                .isInstanceOf(AccesoDenegadoException.class)
                .extracting(excepcion -> ((AccesoDenegadoException) excepcion).getCodigo())
                .isEqualTo("FORBIDDEN_TURNO_DE_OTRA_TIENDA");

        verify(cajaMovimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("Registrar un movimiento en un turno inexistente responde 404")
    void turnoInexistenteNoAdmiteMovimientos() {
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(USUARIO)).thenReturn(Optional.of(personal()));
        when(cajaSesionRepository.findById(TURNO)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cajaService.registrarMovimiento(USUARIO, TURNO,
                new MovimientoTurnoRequest(TipoMovimientoCaja.INGRESO_MENOR, BigDecimal.TEN, "Sencillo", "SUP-001")))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .extracting(excepcion -> ((RecursoNoEncontradoException) excepcion).getCodigo())
                .isEqualTo("TURNO_NO_ENCONTRADO");

        verify(cajaMovimientoRepository, never()).save(any());
    }

    // ---- ayudas ----

    private void stubTurnoAbierto(CajaSesion sesion) {
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(USUARIO)).thenReturn(Optional.of(personal()));
        when(cajaSesionRepository.findById(TURNO)).thenReturn(Optional.of(sesion));
    }

    private void stubTotales(String ingresos, String egresos) {
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.INGRESO_MENOR)).thenReturn(new BigDecimal(ingresos));
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.SALIDA_GASTO)).thenReturn(new BigDecimal(egresos));
    }

    private void stubGuardado() {
        when(cajaMovimientoRepository.save(any(CajaMovimiento.class))).thenAnswer(invocacion -> {
            CajaMovimiento movimiento = invocacion.getArgument(0);
            movimiento.setIdMovimiento(UUID.randomUUID());
            return movimiento;
        });
    }

    private CajaMovimiento capturarGuardado() {
        ArgumentCaptor<CajaMovimiento> captor = ArgumentCaptor.forClass(CajaMovimiento.class);
        verify(cajaMovimientoRepository).save(captor.capture());
        return captor.getValue();
    }

    private PersonalTienda personal() {
        return PersonalTienda.builder()
                .idPersonal(UUID.randomUUID())
                .usuarioId(USUARIO)
                .tiendaId(TIENDA.toString())
                .codigoVendedor("VEN-001")
                .perfilTienda(PerfilTienda.VENDEDOR)
                .activo(true)
                .build();
    }

    private CajaSesion turnoAbierto() {
        return CajaSesion.builder()
                .idSesion(TURNO)
                .tiendaId(TIENDA.toString())
                .terminalPosCodigo(TERMINAL)
                .vendedorId(USUARIO)
                .fechaHoraApertura(OffsetDateTime.now())
                .saldoInicialEfectivo(new BigDecimal(FONDO_FIJO))
                .estado(EstadoCajaSesion.ABIERTA)
                .build();
    }
}
