package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pe.edu.unmsm.fisi.retail.dto.CierreTurnoRequest;
import pe.edu.unmsm.fisi.retail.dto.CobroTurnoResponse;
import pe.edu.unmsm.fisi.retail.dto.RegistroCobroRequest;
import pe.edu.unmsm.fisi.retail.dto.ReporteZResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.model.CajaSesion;
import pe.edu.unmsm.fisi.retail.model.CobroTurno;
import pe.edu.unmsm.fisi.retail.model.EstadoCajaSesion;
import pe.edu.unmsm.fisi.retail.model.EstadoCobroTurno;
import pe.edu.unmsm.fisi.retail.model.PerfilTienda;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.model.TipoMovimientoCaja;
import pe.edu.unmsm.fisi.retail.repository.CajaMovimientoRepository;
import pe.edu.unmsm.fisi.retail.repository.CajaSesionRepository;
import pe.edu.unmsm.fisi.retail.repository.CobroTurnoRepository;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cobros de venta asociados al turno de caja (F8 + F5): validacion del turno real antes de cobrar,
 * registro idempotente del cobro y lectura del efectivo de ventas en el corte Z.
 */
@ExtendWith(MockitoExtension.class)
class CajaCobroServiceTest {

    private static final UUID USUARIO = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID TIENDA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTRA_TIENDA = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TURNO = UUID.fromString("cccccccc-0000-0000-0001-000000000001");
    private static final UUID OTRO_TURNO = UUID.fromString("cccccccc-0000-0000-0001-000000000002");
    private static final String LLAVE = "idem-0001";

    @Mock
    private CajaSesionRepository cajaSesionRepository;

    @Mock
    private CajaMovimientoRepository cajaMovimientoRepository;

    @Mock
    private CobroTurnoRepository cobroTurnoRepository;

    @Mock
    private PersonalTiendaRepository personalTiendaRepository;

    @InjectMocks
    private CajaService cajaService;

    // ---- validarTurnoParaCobro ----

    @Test
    @DisplayName("Un turno abierto de la tienda del usuario es valido para cobrar")
    void turnoAbiertoEsValido() {
        stubTurno(turno(EstadoCajaSesion.ABIERTA, TIENDA));

        assertThatCode(() -> cajaService.validarTurnoParaCobro(USUARIO, TURNO)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Un turno inexistente no permite cobrar y responde 404")
    void turnoInexistenteNoPermiteCobrar() {
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(USUARIO)).thenReturn(Optional.of(personal()));
        when(cajaSesionRepository.findById(TURNO)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cajaService.validarTurnoParaCobro(USUARIO, TURNO))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .extracting(e -> ((RecursoNoEncontradoException) e).getCodigo())
                .isEqualTo("TURNO_NO_ENCONTRADO");
    }

    @Test
    @DisplayName("Un turno cerrado no permite cobrar y responde 409")
    void turnoCerradoNoPermiteCobrar() {
        stubTurno(turno(EstadoCajaSesion.CERRADA, TIENDA));

        assertThatThrownBy(() -> cajaService.validarTurnoParaCobro(USUARIO, TURNO))
                .isInstanceOf(ConflictoException.class)
                .extracting(e -> ((ConflictoException) e).getCodigo())
                .isEqualTo("TURNO_YA_CERRADO");
    }

    @Test
    @DisplayName("Un turno de otra tienda no permite cobrar y responde 403")
    void turnoAjenoNoPermiteCobrar() {
        stubTurno(turno(EstadoCajaSesion.ABIERTA, OTRA_TIENDA));

        assertThatThrownBy(() -> cajaService.validarTurnoParaCobro(USUARIO, TURNO))
                .isInstanceOf(AccesoDenegadoException.class)
                .extracting(e -> ((AccesoDenegadoException) e).getCodigo())
                .isEqualTo("FORBIDDEN_TURNO_DE_OTRA_TIENDA");
    }

    // ---- registrarCobro ----

    @Test
    @DisplayName("Un cobro en efectivo queda PENDIENTE_CONFIRMACION con el vuelto calculado")
    void cobroEnEfectivoNacePendienteConVuelto() {
        stubTurno(turno(EstadoCajaSesion.ABIERTA, TIENDA));
        when(cobroTurnoRepository.findByIdempotencyKey(LLAVE)).thenReturn(Optional.empty());

        CobroTurnoResponse respuesta = cajaService.registrarCobro(USUARIO, TURNO,
                peticion("EFECTIVO", "116.91", "120.00"));

        ArgumentCaptor<CobroTurno> captor = ArgumentCaptor.forClass(CobroTurno.class);
        verify(cobroTurnoRepository).save(captor.capture());
        CobroTurno guardado = captor.getValue();
        assertThat(guardado.getCajaSesionId()).isEqualTo(TURNO);
        assertThat(guardado.getIdempotencyKey()).isEqualTo(LLAVE);
        assertThat(guardado.getEstado()).isEqualTo(EstadoCobroTurno.PENDIENTE_CONFIRMACION);
        assertThat(guardado.getMontoTotal()).isEqualByComparingTo("116.91");
        assertThat(guardado.getMontoRecibido()).isEqualByComparingTo("120.00");
        assertThat(guardado.getVuelto()).isEqualByComparingTo("3.09");
        assertThat(respuesta.reutilizado()).isFalse();
        assertThat(respuesta.estado()).isEqualTo("PENDIENTE_CONFIRMACION");
    }

    @Test
    @DisplayName("Un cobro con tarjeta no tiene vuelto y recibe el total")
    void cobroConTarjetaNoTieneVuelto() {
        stubTurno(turno(EstadoCajaSesion.ABIERTA, TIENDA));
        when(cobroTurnoRepository.findByIdempotencyKey(LLAVE)).thenReturn(Optional.empty());

        cajaService.registrarCobro(USUARIO, TURNO, peticion("TARJETA_POS", "80.00", null));

        ArgumentCaptor<CobroTurno> captor = ArgumentCaptor.forClass(CobroTurno.class);
        verify(cobroTurnoRepository).save(captor.capture());
        assertThat(captor.getValue().getVuelto()).isEqualByComparingTo("0.00");
        assertThat(captor.getValue().getMontoRecibido()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("Reintentar con la misma llave devuelve el cobro existente y no crea otro")
    void mismaLlaveNoDuplicaElCobro() {
        stubTurno(turno(EstadoCajaSesion.ABIERTA, TIENDA));
        when(cobroTurnoRepository.findByIdempotencyKey(LLAVE))
                .thenReturn(Optional.of(cobroExistente(TURNO, EstadoCobroTurno.PENDIENTE_CONFIRMACION)));

        CobroTurnoResponse respuesta = cajaService.registrarCobro(USUARIO, TURNO,
                peticion("EFECTIVO", "116.91", "120.00"));

        assertThat(respuesta.reutilizado()).isTrue();
        verify(cobroTurnoRepository, never()).save(any());
    }

    @Test
    @DisplayName("Reintentar con la misma llave pero diferente importe o medio de pago responde 409 IDEMPOTENCY_CONFLICT")
    void mismaLlaveConCuerpoDistintoRespondeConflicto() {
        stubTurno(turno(EstadoCajaSesion.ABIERTA, TIENDA));
        CobroTurno cobro = cobroExistente(TURNO, EstadoCobroTurno.PENDIENTE_CONFIRMACION);
        when(cobroTurnoRepository.findByIdempotencyKey(LLAVE)).thenReturn(Optional.of(cobro));

        // Diferente monto
        assertThatThrownBy(() -> cajaService.registrarCobro(USUARIO, TURNO, peticion("EFECTIVO", "200.00", "200.00")))
                .isInstanceOf(ConflictoException.class)
                .extracting(e -> ((ConflictoException) e).getCodigo())
                .isEqualTo("IDEMPOTENCY_CONFLICT");

        // Diferente medio de pago
        assertThatThrownBy(() -> cajaService.registrarCobro(USUARIO, TURNO, peticion("TARJETA_POS", "116.91", null)))
                .isInstanceOf(ConflictoException.class)
                .extracting(e -> ((ConflictoException) e).getCodigo())
                .isEqualTo("IDEMPOTENCY_CONFLICT");

        verify(cobroTurnoRepository, never()).save(any());
    }

    @Test
    @DisplayName("Una llave ya usada en otro turno responde 409")
    void llaveDeOtroTurnoSeRechaza() {
        stubTurno(turno(EstadoCajaSesion.ABIERTA, TIENDA));
        when(cobroTurnoRepository.findByIdempotencyKey(LLAVE))
                .thenReturn(Optional.of(cobroExistente(OTRO_TURNO, EstadoCobroTurno.CONFIRMADO)));

        assertThatThrownBy(() -> cajaService.registrarCobro(USUARIO, TURNO, peticion("EFECTIVO", "50.00", "50.00")))
                .isInstanceOf(ConflictoException.class)
                .extracting(e -> ((ConflictoException) e).getCodigo())
                .isEqualTo("IDEMPOTENCY_KEY_EN_OTRO_TURNO");

        verify(cobroTurnoRepository, never()).save(any());
    }

    @Test
    @DisplayName("Efectivo recibido menor al total responde 422 y no se guarda")
    void efectivoInsuficienteSeRechaza() {
        stubTurno(turno(EstadoCajaSesion.ABIERTA, TIENDA));
        when(cobroTurnoRepository.findByIdempotencyKey(LLAVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cajaService.registrarCobro(USUARIO, TURNO, peticion("EFECTIVO", "100.00", "99.99")))
                .isInstanceOf(ReglaNegocioException.class)
                .extracting(e -> ((ReglaNegocioException) e).getCodigo())
                .isEqualTo("MONTO_INSUFICIENTE");

        verify(cobroTurnoRepository, never()).save(any());
    }

    @Test
    @DisplayName("Un medio de pago desconocido responde 422")
    void medioDePagoInvalidoSeRechaza() {
        stubTurno(turno(EstadoCajaSesion.ABIERTA, TIENDA));
        when(cobroTurnoRepository.findByIdempotencyKey(LLAVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cajaService.registrarCobro(USUARIO, TURNO, peticion("YAPE", "10.00", "10.00")))
                .isInstanceOf(ReglaNegocioException.class)
                .extracting(e -> ((ReglaNegocioException) e).getCodigo())
                .isEqualTo("MEDIO_PAGO_INVALIDO");
    }

    @Test
    @DisplayName("No se registra un cobro en un turno cerrado")
    void turnoCerradoNoAdmiteCobros() {
        stubTurno(turno(EstadoCajaSesion.CERRADA, TIENDA));

        assertThatThrownBy(() -> cajaService.registrarCobro(USUARIO, TURNO, peticion("EFECTIVO", "10.00", "10.00")))
                .isInstanceOf(ConflictoException.class);

        verify(cobroTurnoRepository, never()).save(any());
    }

    // ---- actualizarEstadoCobro ----

    @Test
    @DisplayName("Un cobro pendiente pasa a CONFIRMADO y conserva el pedido de G5")
    void pendientePasaAConfirmado() {
        CobroTurno cobro = cobroExistente(TURNO, EstadoCobroTurno.PENDIENTE_CONFIRMACION);
        stubCobro(cobro, turno(EstadoCajaSesion.ABIERTA, TIENDA));

        CobroTurnoResponse respuesta = cajaService.actualizarEstadoCobro(USUARIO, LLAVE,
                EstadoCobroTurno.CONFIRMADO, "PED-2026-00412");

        assertThat(respuesta.estado()).isEqualTo("CONFIRMADO");
        assertThat(cobro.getPedidoId()).isEqualTo("PED-2026-00412");
        verify(cobroTurnoRepository).save(cobro);
    }

    @Test
    @DisplayName("Un rechazo definitivo de G5 deja el cobro EN_REVISION_SUPERVISOR")
    void rechazoPasaARevisionDelSupervisor() {
        CobroTurno cobro = cobroExistente(TURNO, EstadoCobroTurno.PENDIENTE_CONFIRMACION);
        stubCobro(cobro, turno(EstadoCajaSesion.ABIERTA, TIENDA));

        cajaService.actualizarEstadoCobro(USUARIO, LLAVE, EstadoCobroTurno.EN_REVISION_SUPERVISOR, null);

        assertThat(cobro.getEstado()).isEqualTo(EstadoCobroTurno.EN_REVISION_SUPERVISOR);
    }

    @Test
    @DisplayName("Un cobro CONFIRMADO ya no cambia de estado")
    void confirmadoEsFinal() {
        CobroTurno cobro = cobroExistente(TURNO, EstadoCobroTurno.CONFIRMADO);
        stubCobro(cobro, turno(EstadoCajaSesion.ABIERTA, TIENDA));

        assertThatThrownBy(() -> cajaService.actualizarEstadoCobro(USUARIO, LLAVE, EstadoCobroTurno.ANULADO, null))
                .isInstanceOf(ConflictoException.class)
                .extracting(e -> ((ConflictoException) e).getCodigo())
                .isEqualTo("COBRO_ESTADO_INVALIDO");

        verify(cobroTurnoRepository, never()).save(any());
    }

    @Test
    @DisplayName("No se anula un cobro de un turno ya cerrado")
    void noSeAnulaEnTurnoCerrado() {
        CobroTurno cobro = cobroExistente(TURNO, EstadoCobroTurno.EN_REVISION_SUPERVISOR);
        stubCobro(cobro, turno(EstadoCajaSesion.CERRADA, TIENDA));

        assertThatThrownBy(() -> cajaService.actualizarEstadoCobro(USUARIO, LLAVE, EstadoCobroTurno.ANULADO, null))
                .isInstanceOf(ConflictoException.class)
                .extracting(e -> ((ConflictoException) e).getCodigo())
                .isEqualTo("TURNO_YA_CERRADO");
    }

    @Test
    @DisplayName("Actualizar un cobro inexistente responde 404")
    void cobroInexistenteResponde404() {
        when(cobroTurnoRepository.findByIdempotencyKey(LLAVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cajaService.actualizarEstadoCobro(USUARIO, LLAVE, EstadoCobroTurno.CONFIRMADO, null))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .extracting(e -> ((RecursoNoEncontradoException) e).getCodigo())
                .isEqualTo("COBRO_NO_ENCONTRADO");
    }

    // ---- efectivo de ventas en el corte Z ----

    @Test
    @DisplayName("El Reporte Z suma el efectivo de ventas al saldo que espera el sistema")
    void reporteZIncluyeElEfectivoDeVentas() {
        stubTurno(turno(EstadoCajaSesion.ABIERTA, TIENDA));
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.INGRESO_MENOR)).thenReturn(new BigDecimal("30.00"));
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.SALIDA_GASTO)).thenReturn(new BigDecimal("20.00"));
        when(cobroTurnoRepository.totalEfectivoVigente(TURNO, EstadoCobroTurno.ANULADO)).thenReturn(new BigDecimal("500.00"));
        when(cajaSesionRepository.save(any(CajaSesion.class))).thenAnswer(i -> i.getArgument(0));

        // 200.00 fondo + 500.00 ventas + 30.00 ingresos - 20.00 egresos = 710.00
        ReporteZResponse reporte = cajaService.cerrar(USUARIO, TURNO, new CierreTurnoRequest(new BigDecimal("710.00"), null));

        assertThat(reporte.ventasEfectivoTotal()).isEqualByComparingTo("500.00");
        assertThat(reporte.saldoSistema()).isEqualByComparingTo("710.00");
        assertThat(reporte.diferencia()).isEqualByComparingTo("0.00");
        assertThat(reporte.estado()).isEqualTo("CERRADA");
    }

    @Test
    @DisplayName("Sin cobros en efectivo el Reporte Z conserva el calculo anterior")
    void sinCobrosElCalculoNoCambia() {
        stubTurno(turno(EstadoCajaSesion.ABIERTA, TIENDA));
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.INGRESO_MENOR)).thenReturn(null);
        when(cajaMovimientoRepository.totalPorTipo(TURNO, TipoMovimientoCaja.SALIDA_GASTO)).thenReturn(null);
        when(cobroTurnoRepository.totalEfectivoVigente(TURNO, EstadoCobroTurno.ANULADO)).thenReturn(null);
        when(cajaSesionRepository.save(any(CajaSesion.class))).thenAnswer(i -> i.getArgument(0));

        ReporteZResponse reporte = cajaService.cerrar(USUARIO, TURNO, new CierreTurnoRequest(new BigDecimal("200.00"), null));

        assertThat(reporte.ventasEfectivoTotal()).isEqualByComparingTo("0.00");
        assertThat(reporte.saldoSistema()).isEqualByComparingTo("200.00");
    }

    @ParameterizedTest(name = "§2.4: cambiar un cobro exige perfil activo, usuario={0}")
    @NullSource
    @ValueSource(strings = {"cccccccc-0000-0000-0001-000000000001", "dddddddd-0000-0000-0000-000000000001"})
    void actualizarEstadoSinPerfilSeRechaza(String identificador) {
        UUID usuario = identificador == null ? null : UUID.fromString(identificador);
        CobroTurno cobro = cobroExistente(TURNO, EstadoCobroTurno.PENDIENTE_CONFIRMACION);
        when(cobroTurnoRepository.findByIdempotencyKey(LLAVE)).thenReturn(Optional.of(cobro));
        when(cajaSesionRepository.findById(TURNO)).thenReturn(Optional.of(turno(EstadoCajaSesion.ABIERTA, TIENDA)));

        assertThatThrownBy(() -> cajaService.actualizarEstadoCobro(usuario, LLAVE,
                EstadoCobroTurno.CONFIRMADO, "PED-2026-00001"))
                .isInstanceOf(AccesoDenegadoException.class)
                .hasFieldOrPropertyWithValue("codigo", "FORBIDDEN_USUARIO_SIN_TIENDA");
        assertThat(cobro.getEstado()).isEqualTo(EstadoCobroTurno.PENDIENTE_CONFIRMACION);
        verify(cobroTurnoRepository, never()).save(any());
    }

    @Test
    @DisplayName("§2.4: cambiar un cobro desde otra tienda responde 403 sin modificarlo")
    void actualizarEstadoDeOtraTiendaSeRechaza() {
        CobroTurno cobro = cobroExistente(TURNO, EstadoCobroTurno.PENDIENTE_CONFIRMACION);
        stubCobro(cobro, turno(EstadoCajaSesion.ABIERTA, OTRA_TIENDA));

        assertThatThrownBy(() -> cajaService.actualizarEstadoCobro(USUARIO, LLAVE,
                EstadoCobroTurno.ANULADO, null))
                .isInstanceOf(AccesoDenegadoException.class)
                .hasFieldOrPropertyWithValue("codigo", "FORBIDDEN_TURNO_DE_OTRA_TIENDA");
        assertThat(cobro.getEstado()).isEqualTo(EstadoCobroTurno.PENDIENTE_CONFIRMACION);
        verify(cobroTurnoRepository, never()).save(any());
    }

    // ---- ayudas ----

    private void stubTurno(CajaSesion sesion) {
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(USUARIO)).thenReturn(Optional.of(personal()));
        when(cajaSesionRepository.findById(TURNO)).thenReturn(Optional.of(sesion));
    }

    private void stubCobro(CobroTurno cobro, CajaSesion sesion) {
        when(cobroTurnoRepository.findByIdempotencyKey(LLAVE)).thenReturn(Optional.of(cobro));
        when(cajaSesionRepository.findById(cobro.getCajaSesionId())).thenReturn(Optional.of(sesion));
        when(personalTiendaRepository.findByUsuarioIdAndActivoTrue(USUARIO)).thenReturn(Optional.of(personal()));
    }

    private RegistroCobroRequest peticion(String medio, String total, String recibido) {
        return new RegistroCobroRequest(LLAVE, medio, new BigDecimal(total),
                recibido == null ? null : new BigDecimal(recibido), null, "B001", "00000045");
    }

    private CobroTurno cobroExistente(UUID turno, EstadoCobroTurno estado) {
        return CobroTurno.builder()
                .idCobro(UUID.randomUUID())
                .cajaSesionId(turno)
                .idempotencyKey(LLAVE)
                .medioPago("EFECTIVO")
                .montoTotal(new BigDecimal("116.91"))
                .montoRecibido(new BigDecimal("120.00"))
                .vuelto(new BigDecimal("3.09"))
                .estado(estado)
                .build();
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

    private CajaSesion turno(EstadoCajaSesion estado, UUID tienda) {
        return CajaSesion.builder()
                .idSesion(TURNO)
                .tiendaId(tienda.toString())
                .terminalPosCodigo("POS-01")
                .vendedorId(USUARIO)
                .fechaHoraApertura(OffsetDateTime.now())
                .saldoInicialEfectivo(new BigDecimal("200.00"))
                .estado(estado)
                .build();
    }
}

