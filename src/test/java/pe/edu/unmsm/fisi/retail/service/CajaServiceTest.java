package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pe.edu.unmsm.fisi.retail.dto.AperturaTurnoRequest;
import pe.edu.unmsm.fisi.retail.dto.CierreTurnoRequest;
import pe.edu.unmsm.fisi.retail.dto.MovimientoTurnoRequest;
import pe.edu.unmsm.fisi.retail.dto.MovimientoTurnoResponse;
import pe.edu.unmsm.fisi.retail.dto.ReporteZResponse;
import pe.edu.unmsm.fisi.retail.dto.TurnoCajaResponse;
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
 * Reglas de negocio del control de caja (F8).
 *
 * <p>Criterios de aceptaciÃ³n verificados: un solo turno por terminal ({@code 409}); un egreso nunca
 * supera el efectivo en gaveta ({@code 422}); el arqueo ciega el saldo del sistema hasta el cierre;
 * la caja cuadra cuando el declarado coincide con el sistema y queda {@code OBSERVADA} si hay
 * sobrante o faltante; y un turno ajeno, inexistente o ya cerrado no se puede operar.</p>
 */
@ExtendWith(MockitoExtension.class)
class CajaServiceTest {

    private static final UUID USUARIO = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    /**
     * Tienda como la declara {@code PersonalTienda} (F1), que todavÃ­a la guarda como {@code UUID}.
     * El turno de caja ya la lleva en texto (D-05), asÃ­ que en el constructor de {@code CajaSesion}
     * se usa {@code TIENDA.toString()}: es exactamente el puente que hace {@code CajaService}.
     */
    private static final UUID TIENDA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTRA_TIENDA = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TURNO = UUID.fromString("cccccccc-0000-0000-0001-000000000001");
    private static final String TERMINAL = "POS-01";

    @Mock
    private CajaSesionRepository cajaSesionRepository;

    @Mock
    private CajaMovimientoRepository cajaMovimientoRepository;

    @Mock
    private PersonalTiendaRepository personalTiendaRepository;

    @InjectMocks
    private CajaService cajaService;

    @Test
    @DisplayName("Abre el turno con fondo fijo y lo deja ABIERTA")
    void abreElTurnoConFondoFijo() {
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(USUARIO)).thenReturn(Optional.of(personal()));
        when(cajaSesionRepository.findFirstByTerminalPosCodigoAndEstado(TERMINAL, EstadoCajaSesion.ABIERTA))
                .thenReturn(Optional.empty());
        when(cajaSesionRepository.save(any(CajaSesion.class))).thenAnswer(invocacion -> {
            CajaSesion sesion = invocacion.getArgument(0);
            sesion.setIdSesion(TURNO);
            return sesion;
        });

        TurnoCajaResponse respuesta = cajaService.abrir(USUARIO,
                new AperturaTurnoRequest(TERMINAL, new BigDecimal("200.00")));

        assertThat(respuesta.estado()).isEqualTo("ABIERTA");
        assertThat(respuesta.turnoId()).isEqualTo(TURNO.toString());
        assertThat(respuesta.terminalPos()).isEqualTo(TERMINAL);
        assertThat(respuesta.saldoInicialEfectivo()).isEqualByComparingTo("200.00");
        assertThat(respuesta.fechaHoraApertura()).isNotBlank();
    }

    @Test
    @DisplayName("Abrir una terminal que ya tiene turno responde 409, no un error generico")
    void noPermiteDosTurnosEnLaMismaTerminal() {
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(USUARIO)).thenReturn(Optional.of(personal()));
        when(cajaSesionRepository.findFirstByTerminalPosCodigoAndEstado(TERMINAL, EstadoCajaSesion.ABIERTA))
                .thenReturn(Optional.of(turnoAbierto()));

        assertThatThrownBy(() -> cajaService.abrir(USUARIO, new AperturaTurnoRequest(TERMINAL, BigDecimal.TEN)))
                .isInstanceOf(ConflictoException.class)
                .extracting(excepcion -> ((ConflictoException) excepcion).getCodigo())
                .isEqualTo("CAJA_YA_ABIERTA");

        verify(cajaSesionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Sin turno abierto, la consulta responde CERRADA y no un 404")
    void turnoActualSinTurnoDevuelveCerrada() {
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(USUARIO)).thenReturn(Optional.of(personal()));
        when(cajaSesionRepository.findFirstByTiendaIdAndEstadoOrderByFechaHoraAperturaDesc(TIENDA.toString(), EstadoCajaSesion.ABIERTA))
                .thenReturn(Optional.empty());

        TurnoCajaResponse respuesta = cajaService.turnoActual(USUARIO);

        assertThat(respuesta.estado()).isEqualTo("CERRADA");
        assertThat(respuesta.turnoId()).isNull();
    }

    @Test
    @DisplayName("Un egreso mayor al efectivo en gaveta responde 422")
    void noPermiteGirarMasDeLoQueHayEnGaveta() {
        stubTurnoAbierto();
        // Gaveta = fondo 200.00 + 0 ingresos - 0 egresos.
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.INGRESO_MENOR)).thenReturn(new BigDecimal("0.00"));
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.SALIDA_GASTO)).thenReturn(new BigDecimal("0.00"));

        assertThatThrownBy(() -> cajaService.registrarMovimiento(USUARIO, TURNO, new MovimientoTurnoRequest(
                TipoMovimientoCaja.SALIDA_GASTO, new BigDecimal("500.00"), "Compra de insumos", "SUP-001")))
                .isInstanceOf(ReglaNegocioException.class)
                .extracting(excepcion -> ((ReglaNegocioException) excepcion).getCodigo())
                .isEqualTo("FONDOS_INSUFICIENTES_EN_GAVETA");

        verify(cajaMovimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("Un egreso que cabe en la gaveta se guarda con su autorizacion")
    void registraElEgresoAutorizado() {
        stubTurnoAbierto();
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.INGRESO_MENOR)).thenReturn(new BigDecimal("50.00"));
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.SALIDA_GASTO)).thenReturn(new BigDecimal("0.00"));
        when(cajaMovimientoRepository.save(any(CajaMovimiento.class))).thenAnswer(invocacion -> {
            CajaMovimiento m = invocacion.getArgument(0);
            m.setIdMovimiento(UUID.randomUUID());
            return m;
        });

        MovimientoTurnoResponse respuesta = cajaService.registrarMovimiento(USUARIO, TURNO, new MovimientoTurnoRequest(
                TipoMovimientoCaja.SALIDA_GASTO, new BigDecimal("25.00"), "Compra de bolsas", "SUP-001"));

        ArgumentCaptor<CajaMovimiento> guardado = ArgumentCaptor.forClass(CajaMovimiento.class);
        verify(cajaMovimientoRepository).save(guardado.capture());
        assertThat(guardado.getValue().getTipoMovimiento()).isEqualTo(TipoMovimientoCaja.SALIDA_GASTO);
        assertThat(guardado.getValue().getMonto()).isEqualByComparingTo("25.00");
        assertThat(guardado.getValue().getAutorizadoPorSupervisor()).isEqualTo("SUP-001");
        assertThat(guardado.getValue().getCajaSesionId()).isEqualTo(TURNO);
        assertThat(respuesta.nuevoSaldoEstimado()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("El cierre cuadra la caja cuando el declarado iguala al sistema")
    void cierraLaCajaCuadrada() {
        CajaSesion sesion = stubTurnoAbierto();
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.INGRESO_MENOR)).thenReturn(new BigDecimal("50.00"));
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.SALIDA_GASTO)).thenReturn(new BigDecimal("25.00"));
        when(cajaSesionRepository.save(any(CajaSesion.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        // Sistema = 200.00 fondo + 50.00 ingreso - 25.00 egreso = 225.00
        ReporteZResponse cierre = cajaService.cerrar(USUARIO, TURNO,
                new CierreTurnoRequest(new BigDecimal("225.00"), "Caja cuadrada"));

        assertThat(cierre.saldoSistema()).isEqualByComparingTo("225.00");
        assertThat(cierre.diferencia()).isEqualByComparingTo("0.00");
        assertThat(cierre.estado()).isEqualTo("CERRADA");
        assertThat(cierre.turnoId()).isEqualTo(TURNO.toString());
        assertThat(sesion.getEstado()).isEqualTo(EstadoCajaSesion.CERRADA);
    }

    @Test
    @DisplayName("Un faltante deja la sesion OBSERVADA para el supervisor")
    void marcaObservadaLaCajaConFaltante() {
        stubTurnoAbierto();
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.INGRESO_MENOR)).thenReturn(new BigDecimal("0.00"));
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.SALIDA_GASTO)).thenReturn(new BigDecimal("0.00"));
        when(cajaSesionRepository.save(any(CajaSesion.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        // Sistema = 200.00; el cajero declaro 175.00: faltan 25.00.
        ReporteZResponse cierre = cajaService.cerrar(USUARIO, TURNO,
                new CierreTurnoRequest(new BigDecimal("175.00"), "Falta sencillo"));

        assertThat(cierre.diferencia()).isEqualByComparingTo("-25.00");
        assertThat(cierre.estado()).isEqualTo("OBSERVADA");
    }

    @Test
    @DisplayName("Un sobrante tambien deja la sesion OBSERVADA")
    void marcaObservadaLaCajaConSobrante() {
        stubTurnoAbierto();
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.INGRESO_MENOR)).thenReturn(new BigDecimal("0.00"));
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.SALIDA_GASTO)).thenReturn(new BigDecimal("0.00"));
        when(cajaSesionRepository.save(any(CajaSesion.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        // Sistema = 200.00; el cajero declaro 210.00: sobran 10.00.
        ReporteZResponse cierre = cajaService.cerrar(USUARIO, TURNO,
                new CierreTurnoRequest(new BigDecimal("210.00"), "Sobra sencillo"));

        assertThat(cierre.diferencia()).isEqualByComparingTo("10.00");
        assertThat(cierre.estado()).isEqualTo("OBSERVADA");
    }

    @Test
    @DisplayName("Operar un turno que no existe responde 404")
    void turnoInexistenteResponde404() {
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(USUARIO)).thenReturn(Optional.of(personal()));
        when(cajaSesionRepository.findById(TURNO)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cajaService.cerrar(USUARIO, TURNO, new CierreTurnoRequest(BigDecimal.ZERO, "")))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .extracting(excepcion -> ((RecursoNoEncontradoException) excepcion).getCodigo())
                .isEqualTo("TURNO_NO_ENCONTRADO");
    }

    @Test
    @DisplayName("Operar el turno de otra tienda responde 403")
    void turnoDeOtraTiendaResponde403() {
        CajaSesion ajena = turnoAbierto();
        ajena.setTiendaId(OTRA_TIENDA.toString());
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(USUARIO)).thenReturn(Optional.of(personal()));
        when(cajaSesionRepository.findById(TURNO)).thenReturn(Optional.of(ajena));

        assertThatThrownBy(() -> cajaService.registrarMovimiento(USUARIO, TURNO, new MovimientoTurnoRequest(
                TipoMovimientoCaja.INGRESO_MENOR, new BigDecimal("10.00"), "Sencillo", "SUP-001")))
                .isInstanceOf(AccesoDenegadoException.class)
                .extracting(excepcion -> ((AccesoDenegadoException) excepcion).getCodigo())
                .isEqualTo("FORBIDDEN_TURNO_DE_OTRA_TIENDA");
    }

    @Test
    @DisplayName("Un turno ya cerrado no admite mas operaciones")
    void turnoYaCerradoResponde409() {
        CajaSesion cerrada = turnoAbierto();
        cerrada.setEstado(EstadoCajaSesion.CERRADA);
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(USUARIO)).thenReturn(Optional.of(personal()));
        when(cajaSesionRepository.findById(TURNO)).thenReturn(Optional.of(cerrada));

        assertThatThrownBy(() -> cajaService.cerrar(USUARIO, TURNO, new CierreTurnoRequest(BigDecimal.TEN, "")))
                .isInstanceOf(ConflictoException.class)
                .extracting(excepcion -> ((ConflictoException) excepcion).getCodigo())
                .isEqualTo("TURNO_YA_CERRADO");
    }

    @Test
    @DisplayName("Quien no es personal activo de la tienda recibe 403 y no toca el efectivo")
    void usuarioSinPerfilRecibe403() {
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cajaService.turnoActual(USUARIO))
                .isInstanceOf(AccesoDenegadoException.class)
                .extracting(excepcion -> ((AccesoDenegadoException) excepcion).getCodigo())
                .isEqualTo("FORBIDDEN_USUARIO_SIN_TIENDA");
    }

    /** Prepara el turno abierto de la tienda del usuario y lo devuelve para inspeccionarlo. */
    private CajaSesion stubTurnoAbierto() {
        CajaSesion sesion = turnoAbierto();
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(USUARIO)).thenReturn(Optional.of(personal()));
        when(cajaSesionRepository.findById(TURNO)).thenReturn(Optional.of(sesion));
        return sesion;
    }

    private PersonalTienda personal() {
        return PersonalTienda.builder()
                .idPersonal(UUID.randomUUID())
                .usuarioId(USUARIO)
                .tiendaId(TIENDA)
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
                .saldoInicialEfectivo(new BigDecimal("200.00"))
                .estado(EstadoCajaSesion.ABIERTA)
                .build();
    }
}
