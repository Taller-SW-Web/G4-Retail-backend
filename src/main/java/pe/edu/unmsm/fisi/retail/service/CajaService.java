package pe.edu.unmsm.fisi.retail.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.model.TipoMovimientoCaja;
import pe.edu.unmsm.fisi.retail.repository.CajaMovimientoRepository;
import pe.edu.unmsm.fisi.retail.repository.CajaSesionRepository;
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
     * Cobros en efectivo del turno.
     *
     * <p><strong>TODO (F5):</strong> integrar los cobros en efectivo del turno. Los cobros no viven
     * en esta base ni son un movimiento de caja: los administra el módulo de Ventas. El reparto lo
     * dice al cruzar F5 con F8 ("Con Miguel: para que las ventas en efectivo sumen al saldo en caja
     * del turno"). Hasta que F5 publique ese total, la promesa de F8 se limita a fondo fijo +
     * movimientos menores, y así lo refleja el Reporte Z.</p>
     */
    private BigDecimal ventasEnEfectivo(CajaSesion sesion) {
        return CERO;
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
        String tiendaId = tiendaDe(usuarioId);

        CajaSesion sesion = cajaSesionRepository.findById(turnoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("TURNO_NO_ENCONTRADO",
                        "El turno de caja indicado no existe"));

        if (!sesion.getTiendaId().equals(tiendaId)) {
            throw new AccesoDenegadoException("FORBIDDEN_TURNO_DE_OTRA_TIENDA",
                    "El turno indicado pertenece a otra tienda");
        }
        if (sesion.getEstado() != EstadoCajaSesion.ABIERTA) {
            throw new ConflictoException("TURNO_YA_CERRADO",
                    "El turno ya fue cerrado y no admite mas operaciones");
        }
        return sesion;
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
