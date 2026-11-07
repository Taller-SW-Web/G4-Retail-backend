package pe.edu.unmsm.fisi.retail.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unmsm.fisi.retail.dto.AperturaTurnoRequest;
import pe.edu.unmsm.fisi.retail.dto.CierreTurnoRequest;
import pe.edu.unmsm.fisi.retail.dto.CobroTurnoResponse;
import pe.edu.unmsm.fisi.retail.dto.MovimientoTurnoRequest;
import pe.edu.unmsm.fisi.retail.dto.MovimientoTurnoResponse;
import pe.edu.unmsm.fisi.retail.dto.RegistroCobroRequest;
import pe.edu.unmsm.fisi.retail.dto.ReporteZResponse;
import pe.edu.unmsm.fisi.retail.dto.TurnoCajaResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.model.CajaMovimiento;
import pe.edu.unmsm.fisi.retail.model.CajaSesion;
import pe.edu.unmsm.fisi.retail.model.CobroTurno;
import pe.edu.unmsm.fisi.retail.model.EstadoCajaSesion;
import pe.edu.unmsm.fisi.retail.model.EstadoCobroTurno;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.model.TipoMovimientoCaja;
import pe.edu.unmsm.fisi.retail.repository.CajaMovimientoRepository;
import pe.edu.unmsm.fisi.retail.repository.CajaSesionRepository;
import pe.edu.unmsm.fisi.retail.repository.CobroTurnoRepository;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Control de turno y cuadre de caja (F8): RF-20 apertura, RF-21 movimientos menores y RF-22 arqueo
 * ciego con Reporte Z.
 *
 * <p>Todo el efectivo de la tienda pasa por aquí. Tres reglas gobiernan la clase:</p>
 * <ol>
 *   <li><strong>Un solo turno por terminal.</strong> Abrir donde ya hay un turno es un
 *       {@code 409}, no un error de validación: la petición está bien, el momento no.</li>
 *   <li><strong>No se gira lo que no hay.</strong> Un egreso mayor al efectivo en gaveta es un
 *       {@code 422} (RF-21).</li>
 *   <li><strong>El arqueo es ciego.</strong> El saldo del sistema se calcula al cerrar y solo se
 *       devuelve después de recibir el conteo del cajero; ningún endpoint anterior lo expone.</li>
 * </ol>
 *
 * <p>La auditoría de APERTURA y CIERRE no se escribe aquí: la hacen los triggers
 * {@code trg_ret_caja_sesion_audit_ins/upd} de {@code schema.sql}. Duplicarla sería doble
 * contabilidad.</p>
 */
@Service
@RequiredArgsConstructor
public class CajaService {

    private static final BigDecimal CERO = new BigDecimal("0.00");

    private final CajaSesionRepository cajaSesionRepository;
    private final CajaMovimientoRepository cajaMovimientoRepository;
    private final PersonalTiendaRepository personalTiendaRepository;
    private final CobroTurnoRepository cobroTurnoRepository;

    /**
     * Turno vigente de la tienda del usuario autenticado.
     *
     * <p>Si no hay ninguno abierto devuelve {@code estado = CERRADA} en lugar de un {@code 404}: la
     * SPA pregunta "¿hay turno?" en cada entrada al mostrador, y "no hay" es una respuesta normal,
     * no un error.</p>
     *
     * @param usuarioId {@code sub} del JWT
     */
    @Transactional(readOnly = true)
    public TurnoCajaResponse turnoActual(UUID usuarioId) {
        String tiendaId = tiendaDe(usuarioId);

        return cajaSesionRepository
                .findFirstByTiendaIdAndEstadoOrderByFechaHoraAperturaDesc(tiendaId, EstadoCajaSesion.ABIERTA)
                .map(sesion -> TurnoCajaResponse.desde(sesion, codigoDe(sesion.getVendedorId())))
                .orElseGet(TurnoCajaResponse::sinTurno);
    }

    /**
     * Abre el turno con fondo fijo (RF-20).
     *
     * @throws ConflictoException {@code CAJA_YA_ABIERTA} si la terminal ya tiene un turno abierto
     */
    @Transactional
    public TurnoCajaResponse abrir(UUID usuarioId, AperturaTurnoRequest peticion) {
        String tiendaId = tiendaDe(usuarioId);

        cajaSesionRepository.findFirstByTerminalPosCodigoAndEstado(peticion.terminalPos(), EstadoCajaSesion.ABIERTA)
                .ifPresent(existente -> {
                    throw new ConflictoException("CAJA_YA_ABIERTA",
                            "La terminal " + existente.getTerminalPosCodigo()
                                    + " ya cuenta con un turno abierto. Debe cerrarlo antes de iniciar uno nuevo");
                });

        CajaSesion sesion = CajaSesion.builder()
                .tiendaId(tiendaId)
                .terminalPosCodigo(peticion.terminalPos())
                .vendedorId(usuarioId)
                .fechaHoraApertura(OffsetDateTime.now())
                .saldoInicialEfectivo(escala(peticion.saldoInicialEfectivo()))
                .estado(EstadoCajaSesion.ABIERTA)
                .build();

        // El INSERT dispara trg_ret_caja_sesion_audit_ins, que deja la APERTURA en auditoría.
        cajaSesionRepository.save(sesion);
        return TurnoCajaResponse.desde(sesion, codigoDe(usuarioId));
    }

    /**
     * Registra un ingreso o egreso menor de caja chica (RF-21).
     *
     * @throws RecursoNoEncontradoException {@code TURNO_NO_ENCONTRADO} si el turno no existe
     * @throws AccesoDenegadoException      {@code FORBIDDEN_TURNO_DE_OTRA_TIENDA} si no es de su tienda
     * @throws ConflictoException           {@code TURNO_YA_CERRADO} si el turno ya se cerró
     * @throws ReglaNegocioException        {@code FONDOS_INSUFICIENTES_EN_GAVETA} si el egreso no cabe
     */
    @Transactional
    public MovimientoTurnoResponse registrarMovimiento(UUID usuarioId, UUID turnoId, MovimientoTurnoRequest peticion) {
        CajaSesion sesion = turnoAbierto(usuarioId, turnoId);
        BigDecimal disponible = saldoEnGaveta(sesion);
        BigDecimal monto = escala(peticion.monto());

        if (peticion.tipoMovimiento() == TipoMovimientoCaja.SALIDA_GASTO && monto.compareTo(disponible) > 0) {
            throw new ReglaNegocioException("FONDOS_INSUFICIENTES_EN_GAVETA",
                    "El efectivo en gaveta no alcanza para el egreso: disponible S/ " + disponible
                            + ", solicitado S/ " + monto);
        }

        CajaMovimiento movimiento = CajaMovimiento.builder()
                .cajaSesionId(sesion.getIdSesion())
                .tipoMovimiento(peticion.tipoMovimiento())
                .monto(monto)
                .motivo(peticion.motivo())
                .autorizadoPorSupervisor(peticion.autorizadoPor())
                .fechaHora(OffsetDateTime.now())
                .build();
        cajaMovimientoRepository.save(movimiento);

        return MovimientoTurnoResponse.desde(movimiento, saldoEnGaveta(sesion));
    }

    /**
     * Cierra el turno con arqueo ciego y devuelve el Reporte Z (RF-22).
     *
     * <p>El saldo del sistema se calcula con lo que hay en la base justo antes de comparar, de modo
     * que el conteo del cajero nunca pudo influir en él. La diferencia queda clasificada como
     * <strong>faltante</strong> (negativa) o <strong>sobrante</strong> (positiva).</p>
     *
     * @throws RecursoNoEncontradoException {@code TURNO_NO_ENCONTRADO} si el turno no existe
     * @throws AccesoDenegadoException      {@code FORBIDDEN_TURNO_DE_OTRA_TIENDA} si no es de su tienda
     * @throws ConflictoException           {@code TURNO_YA_CERRADO} si el turno ya se cerró
     */
    @Transactional
    public ReporteZResponse cerrar(UUID usuarioId, UUID turnoId, CierreTurnoRequest peticion) {
        CajaSesion sesion = turnoAbierto(usuarioId, turnoId);

        BigDecimal ingresos = ingresosMenores(sesion);
        BigDecimal egresos = egresosMenores(sesion);
        BigDecimal ventasEfectivo = ventasEnEfectivo(sesion);
        BigDecimal saldoSistema = sesion.getSaldoInicialEfectivo()
                .add(ventasEfectivo)
                .add(ingresos)
                .subtract(egresos);

        BigDecimal declarado = escala(peticion.montoDeclarado());
        BigDecimal diferencia = declarado.subtract(saldoSistema);

        sesion.setFechaHoraCierre(OffsetDateTime.now());
        sesion.setSaldoFinalDeclarado(declarado);
        sesion.setSaldoFinalSistema(saldoSistema);
        sesion.setDiferenciaSaldo(diferencia);
        sesion.setObservacionesCierre(peticion.observaciones());
        sesion.setEstado(diferencia.signum() == 0 ? EstadoCajaSesion.CERRADA : EstadoCajaSesion.OBSERVADA);

        // El UPDATE dispara trg_ret_caja_sesion_audit_upd, que deja el CIERRE (EXITOSO u OBSERVADO).
        cajaSesionRepository.save(sesion);

        return ReporteZResponse.desde(sesion, ventasEfectivo, ingresos, egresos, saldoSistema);
    }

    /**
     * Valida contra la base de datos que el turno exista y siga abierto.
     *
     * @param turnoId identificador del turno de caja
     * @return sesión de caja abierta
     * @throws RecursoNoEncontradoException {@code TURNO_NO_ENCONTRADO}
     * @throws ConflictoException           {@code TURNO_YA_CERRADO}
     */
    @Transactional(readOnly = true)
    public CajaSesion validarTurnoAbierto(UUID turnoId) {
        CajaSesion sesion = cajaSesionRepository.findById(turnoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("TURNO_NO_ENCONTRADO",
                        "El turno de caja indicado no existe"));
        if (sesion.getEstado() != EstadoCajaSesion.ABIERTA) {
            throw new ConflictoException("TURNO_YA_CERRADO",
                    "El turno ya fue cerrado y no admite mas operaciones");
        }
        return sesion;
    }

    /**
     * Valida, contra la base, que el turno exista, sea de la tienda del usuario y siga abierto.
     *
     * <p>Es lo que {@code VentaService} debe llamar <strong>antes de cobrar</strong>, en lugar de la
     * lista de turnos en memoria (mock de la Semana 8).</p>
     *
     * @throws RecursoNoEncontradoException {@code TURNO_NO_ENCONTRADO}
     * @throws AccesoDenegadoException      {@code FORBIDDEN_TURNO_DE_OTRA_TIENDA}
     * @throws ConflictoException           {@code TURNO_YA_CERRADO}
     */
    @Transactional(readOnly = true)
    public void validarTurnoParaCobro(UUID usuarioId, UUID turnoId) {
        validarTurnoParaCobro(usuarioId, turnoId, null);
    }

    /**
     * Igual que {@link #validarTurnoParaCobro(UUID, UUID)}, y además exige que la venta venga de la
     * terminal en la que se abrió el turno.
     *
     * @param terminalPos terminal del request; {@code null} no valida la terminal
     * @throws ReglaNegocioException {@code TERMINAL_NO_COINCIDE}, mas los codigos de
     *                               {@link #validarTurnoParaCobro(UUID, UUID)}
     */
    @Transactional(readOnly = true)
    public void validarTurnoParaCobro(UUID usuarioId, UUID turnoId, String terminalPos) {
        turnoAbiertoEnTerminal(usuarioId, turnoId, terminalPos);
    }

    /**
     * Autoriza la lectura de una venta ya procesada contra su tienda y terminal originales.
     * El turno puede estar cerrado: devolver el resultado original no registra un cobro nuevo
     * (contrato §2.3 y §2.4).
     */
    @Transactional(readOnly = true)
    public void validarAccesoTurno(UUID usuarioId, UUID turnoId, String terminalPos) {
        validarTerminal(turnoDeTienda(usuarioId, turnoId), terminalPos);
    }

    /**
     * Overload de compatibilidad para tests existentes que no pasan terminalPos.
     * Delega a la implementacion principal con {@code terminalPos = null} (sin validacion de terminal).
     */
    @Transactional
    public CobroTurnoResponse registrarCobro(UUID usuarioId, UUID turnoId, RegistroCobroRequest peticion) {
        return registrarCobro(usuarioId, turnoId, null, peticion);
    }

    /**
     * Deja registrado el cobro de una venta en el turno, para que el efectivo cuente en el arqueo.
     *
     * <p>Es idempotente: si ya existe un cobro con la misma {@code idempotencyKey} en este turno, lo
     * devuelve con {@code reutilizado = true} y no crea otro. El cobro nace
     * {@code PENDIENTE_CONFIRMACION} salvo que quien lo registra ya conozca su estado (ver la sobrecarga con
     * {@code estadoInicial}); {@link #actualizarEstadoCobro} lo mueve despues.</p>
     *
     * @throws ReglaNegocioException {@code MEDIO_PAGO_INVALIDO}, {@code MONTO_COBRO_INVALIDO} o
     *                               {@code MONTO_INSUFICIENTE} (efectivo recibido menor al total)
     * @throws ConflictoException    {@code IDEMPOTENCY_KEY_EN_OTRO_TURNO} si la llave pertenece a otro
     *                               turno, mas los codigos de {@link #validarTurnoParaCobro}
     */
    @Transactional
    public CobroTurnoResponse registrarCobro(UUID usuarioId, UUID turnoId, String terminalPos, RegistroCobroRequest peticion) {
        return registrarCobro(usuarioId, turnoId, terminalPos, peticion, EstadoCobroTurno.PENDIENTE_CONFIRMACION);
    }

    /**
     * Registra el cobro con el estado que ya conoce quien lo registra. La venta de mostrador lo
     * registra despues de que Ventas confirma el pedido, asi que lo pide {@code CONFIRMADO}; si la
     * misma clave quedo antes pendiente, el reintento lo confirma.
     */
    @Transactional
    public CobroTurnoResponse registrarCobro(UUID usuarioId, UUID turnoId, String terminalPos,
                                             RegistroCobroRequest peticion, EstadoCobroTurno estadoInicial) {
        CajaSesion sesion = turnoAbiertoEnTerminal(usuarioId, turnoId, terminalPos);

        var existente = cobroTurnoRepository.findByIdempotencyKey(peticion.idempotencyKey());
        if (existente.isPresent()) {
            CobroTurno cobro = existente.get();
            if (!cobro.getCajaSesionId().equals(sesion.getIdSesion())) {
                throw new ConflictoException("IDEMPOTENCY_KEY_EN_OTRO_TURNO",
                        "La llave de idempotencia ya se uso en otro turno de caja");
            }
            // Contrato §2.3: Repetir la misma clave con un cuerpo distinto responde 409 IDEMPOTENCY_CONFLICT
            if (peticion.medioPago() != null && !cobro.getMedioPago().equals(peticion.medioPago())) {
                throw new ConflictoException("IDEMPOTENCY_CONFLICT",
                        "La llave de idempotencia ya fue utilizada con otro medio de pago");
            }
            if (peticion.montoTotal() != null && cobro.getMontoTotal().compareTo(escala(peticion.montoTotal())) != 0) {
                throw new ConflictoException("IDEMPOTENCY_CONFLICT",
                        "La llave de idempotencia ya fue utilizada con un monto diferente");
            }
            if (estadoInicial == EstadoCobroTurno.CONFIRMADO
                    && cobro.getEstado() == EstadoCobroTurno.PENDIENTE_CONFIRMACION) {
                cobro.setEstado(EstadoCobroTurno.CONFIRMADO);
                if (peticion.pedidoId() != null) {
                    cobro.setPedidoId(peticion.pedidoId());
                }
                cobroTurnoRepository.save(cobro);
            }
            return CobroTurnoResponse.desde(cobro, true);
        }

        String medioPago = peticion.medioPago();
        if (!CobroTurno.EFECTIVO.equals(medioPago) && !CobroTurno.TARJETA_POS.equals(medioPago)) {
            throw new ReglaNegocioException("MEDIO_PAGO_INVALIDO",
                    "El medio de pago debe ser EFECTIVO o TARJETA_POS");
        }
        if (peticion.montoTotal() == null || peticion.montoTotal().signum() <= 0) {
            throw new ReglaNegocioException("MONTO_COBRO_INVALIDO", "El total del cobro debe ser mayor que cero");
        }

        BigDecimal total = escala(peticion.montoTotal());
        BigDecimal recibido = total;
        BigDecimal vuelto = CERO;
        if (CobroTurno.EFECTIVO.equals(medioPago)) {
            if (peticion.montoRecibido() == null || escala(peticion.montoRecibido()).compareTo(total) < 0) {
                throw new ReglaNegocioException("MONTO_INSUFICIENTE",
                        "El efectivo recibido no cubre el total del cobro (S/ " + total + ")");
            }
            recibido = escala(peticion.montoRecibido());
            vuelto = recibido.subtract(total);
        }

        CobroTurno cobro = CobroTurno.builder()
                .cajaSesionId(sesion.getIdSesion())
                .idempotencyKey(peticion.idempotencyKey())
                .pedidoId(peticion.pedidoId())
                .comprobanteSerie(peticion.comprobanteSerie())
                .comprobanteNumero(peticion.comprobanteNumero())
                .medioPago(medioPago)
                .montoTotal(total)
                .montoRecibido(recibido)
                .vuelto(vuelto)
                .estado(estadoInicial)
                .build();
        cobroTurnoRepository.save(cobro);
        return CobroTurnoResponse.desde(cobro, false);
    }

    /**
     * Mueve el cobro a su estado segun la respuesta de Ventas (G5).
     *
     * <p>Transiciones: {@code PENDIENTE_CONFIRMACION} pasa a cualquier otro; {@code EN_REVISION_SUPERVISOR}
     * pasa a {@code CONFIRMADO} o {@code ANULADO}; {@code CONFIRMADO} y {@code ANULADO} son finales.
     * Anular un cobro de un turno ya cerrado no se permite: el arqueo ya se hizo con ese efectivo.</p>
     *
     * @param usuarioId usuario autenticado; debe pertenecer a la misma tienda del turno
     * @param idempotencyKey llave del cobro
     * @param nuevoEstado nuevo estado deseado
     * @param pedidoId pedido de G5, si ya se conoce; {@code null} deja el que tenia
     * @throws RecursoNoEncontradoException {@code COBRO_NO_ENCONTRADO} o {@code TURNO_NO_ENCONTRADO}
     * @throws AccesoDenegadoException      {@code FORBIDDEN_USUARIO_SIN_TIENDA} o {@code FORBIDDEN_TURNO_DE_OTRA_TIENDA}
     * @throws ConflictoException           {@code COBRO_ESTADO_INVALIDO} o {@code TURNO_YA_CERRADO}
     */
    @Transactional
    public CobroTurnoResponse actualizarEstadoCobro(UUID usuarioId, String idempotencyKey,
                                                    EstadoCobroTurno nuevoEstado, String pedidoId) {
        CobroTurno cobro = cobroTurnoRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new RecursoNoEncontradoException("COBRO_NO_ENCONTRADO",
                        "No existe un cobro con esa llave de idempotencia"));

        CajaSesion sesion = cajaSesionRepository.findById(cobro.getCajaSesionId())
                .orElseThrow(() -> new RecursoNoEncontradoException("TURNO_NO_ENCONTRADO",
                        "El turno de caja indicado no existe"));
        if (!sesion.getTiendaId().equals(tiendaDe(usuarioId))) {
            throw new AccesoDenegadoException("FORBIDDEN_TURNO_DE_OTRA_TIENDA",
                    "El turno indicado pertenece a otra tienda");
        }

        EstadoCobroTurno actual = cobro.getEstado();
        boolean permitido = switch (actual) {
            case PENDIENTE_CONFIRMACION -> nuevoEstado != EstadoCobroTurno.PENDIENTE_CONFIRMACION;
            case EN_REVISION_SUPERVISOR -> nuevoEstado == EstadoCobroTurno.CONFIRMADO
                    || nuevoEstado == EstadoCobroTurno.ANULADO;
            case CONFIRMADO, ANULADO -> false;
        };
        if (!permitido) {
            throw new ConflictoException("COBRO_ESTADO_INVALIDO",
                    "El cobro no puede pasar de " + actual + " a " + nuevoEstado);
        }
        if (nuevoEstado == EstadoCobroTurno.ANULADO && sesion.getEstado() != EstadoCajaSesion.ABIERTA) {
            throw new ConflictoException("TURNO_YA_CERRADO",
                    "No se puede anular un cobro de un turno ya cerrado");
        }

        cobro.setEstado(nuevoEstado);
        if (pedidoId != null) {
            cobro.setPedidoId(pedidoId);
        }
        cobroTurnoRepository.save(cobro);
        return CobroTurnoResponse.desde(cobro, false);
    }

    /**
     * Efectivo cobrado por ventas en el turno: suma de los cobros en efectivo que no estan anulados.
     *
     * <p>Se cuenta el total y no lo recibido (el vuelto ya salio de la gaveta), y se incluyen los
     * cobros pendientes o en revision: el dinero ya esta en la gaveta aunque G5 no haya confirmado.</p>
     */
    private BigDecimal ventasEnEfectivo(CajaSesion sesion) {
        return nullSeguro(cobroTurnoRepository.totalEfectivoVigente(sesion.getIdSesion(), EstadoCobroTurno.ANULADO));
    }

    /** Fondo fijo + ventas en efectivo + ingresos menores - egresos menores. */
    private BigDecimal saldoEnGaveta(CajaSesion sesion) {
        return sesion.getSaldoInicialEfectivo()
                .add(ventasEnEfectivo(sesion))
                .add(ingresosMenores(sesion))
                .subtract(egresosMenores(sesion));
    }

    private BigDecimal ingresosMenores(CajaSesion sesion) {
        return nullSeguro(cajaMovimientoRepository.totalPorTipo(sesion.getIdSesion(), TipoMovimientoCaja.INGRESO_MENOR));
    }

    private BigDecimal egresosMenores(CajaSesion sesion) {
        return nullSeguro(cajaMovimientoRepository.totalPorTipo(sesion.getIdSesion(), TipoMovimientoCaja.SALIDA_GASTO));
    }

    /**
     * Turno indicado por la ruta, validado contra la tienda del usuario y contra su estado.
     *
     * <p>Los tres controles van juntos porque son las tres formas de pedir un turno que no se puede
     * operar: no existe, no es tuyo, o ya se cerró.</p>
     */
    private CajaSesion turnoAbierto(UUID usuarioId, UUID turnoId) {
        CajaSesion sesion = turnoDeTienda(usuarioId, turnoId);
        if (sesion.getEstado() != EstadoCajaSesion.ABIERTA) {
            throw new ConflictoException("TURNO_YA_CERRADO",
                    "El turno ya fue cerrado y no admite mas operaciones");
        }
        return sesion;
    }

    private CajaSesion turnoDeTienda(UUID usuarioId, UUID turnoId) {
        String tiendaId = tiendaDe(usuarioId);

        CajaSesion sesion = cajaSesionRepository.findById(turnoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("TURNO_NO_ENCONTRADO",
                        "El turno de caja indicado no existe"));

        if (!sesion.getTiendaId().equals(tiendaId)) {
            throw new AccesoDenegadoException("FORBIDDEN_TURNO_DE_OTRA_TIENDA",
                    "El turno indicado pertenece a otra tienda");
        }
        return sesion;
    }

    /** {@link #turnoAbierto} y, si se indica {@code terminalPos}, que coincida con la del turno. */
    private CajaSesion turnoAbiertoEnTerminal(UUID usuarioId, UUID turnoId, String terminalPos) {
        CajaSesion sesion = turnoAbierto(usuarioId, turnoId);
        validarTerminal(sesion, terminalPos);
        return sesion;
    }

    private void validarTerminal(CajaSesion sesion, String terminalPos) {
        if (terminalPos != null && !sesion.getTerminalPosCodigo().equals(terminalPos)) {
            throw new ReglaNegocioException("TERMINAL_NO_COINCIDE",
                    "La terminal '" + terminalPos + "' no coincide con la del turno ("
                    + sesion.getTerminalPosCodigo() + ")");
        }
    }

    /**
     * Codigo de tienda del usuario autenticado.
     *
     * <p>Se reutiliza la resolucion de perfil de F1: quien no es personal activo de la tienda no
     * puede tocar el efectivo, y recibe {@code 403}. El reparto lo pide asi: "Con Cristhian: para
     * asociar el turno de caja al vendedor logueado".</p>
     *
     * <p><strong>Puente temporal de D-05.</strong> Esta capa ya trabaja con el codigo textual de la
     * tienda ({@code store_code}), que es el acuerdo entre modulos. Pero {@code PersonalTienda} —
     * entidad de F1, no de F8 — todavia declara {@code tiendaId} como {@code UUID} mientras su dueno
     * no aplique su parte de la migracion. El {@code toString()} existe solo para cruzar esa
     * frontera; el dia que {@code PersonalTienda.tiendaId} sea {@code String}, se borra y no queda
     * ningun otro cambio. Ver {@code PARCHE-PersonalTienda.md}.</p>
     */
    private String tiendaDe(UUID usuarioId) {
        return personalTiendaRepository.findByUsuarioIdAndActivoTrue(usuarioId)
                .map(PersonalTienda::getTiendaId)
                .orElseThrow(() -> new AccesoDenegadoException("FORBIDDEN_USUARIO_SIN_TIENDA",
                        "El usuario autenticado no tiene un perfil activo en la tienda"));
    }

    /** Código visible ({@code VEN-001}) de un vendedor, o {@code null} si ya no es personal activo. */
    private String codigoDe(UUID usuarioId) {
        return personalTiendaRepository.findByUsuarioIdAndActivoTrue(usuarioId)
                .map(PersonalTienda::getCodigoVendedor)
                .orElse(null);
    }

    private BigDecimal escala(BigDecimal monto) {
        return monto.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal nullSeguro(BigDecimal monto) {
        return monto == null ? CERO : monto;
    }
}
