package pe.edu.unmsm.fisi.retail.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unmsm.fisi.retail.dto.IncidenciaResponse;
import pe.edu.unmsm.fisi.retail.dto.RegistroIncidenciaRequest;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.ParametroInvalidoException;
import pe.edu.unmsm.fisi.retail.integracion.inventario.IncidenciaReportadaPayload;
import pe.edu.unmsm.fisi.retail.model.EstadoCajaSesion;
import pe.edu.unmsm.fisi.retail.model.EstadoIncidencia;
import pe.edu.unmsm.fisi.retail.model.IncidenciaInventario;
import pe.edu.unmsm.fisi.retail.model.PerfilTienda;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.repository.CajaSesionRepository;
import pe.edu.unmsm.fisi.retail.repository.IncidenciaInventarioRepository;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;
import pe.edu.unmsm.fisi.retail.service.outbox.OutboxPublicador;
import pe.edu.unmsm.fisi.retail.service.outbox.TipoEventoOutbox;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * Reporte de prendas dañadas o no ubicadas en el mostrador (F12, RF-32; contrato §5.12).
 *
 * <p>Guarda la incidencia en {@code PENDIENTE_REPORTE} y, <strong>en la misma transacción</strong>,
 * deja en el outbox el evento {@code INCIDENCIA_INVENTARIO_REPORTADA} para Inventory (G6). Desde ese
 * momento el SKU queda bloqueado para la venta en la tienda (RF-33). Cuando Inventory acepta el reporte,
 * {@link CuarentenaService} pasa la incidencia a {@code EN_CUARENTENA}.</p>
 */
@Slf4j
@Service
public class IncidenciaInventarioService {

    private static final int MIN_OBSERVACION = 5;
    private static final int MAX_NOMBRE = 150;

    private final IncidenciaInventarioRepository incidencias;
    private final PersonalTiendaRepository personal;
    private final CajaSesionRepository turnos;
    private final CodigosSucursal codigosSucursal;
    private final CatalogoService catalogo;
    private final OutboxPublicador outbox;
    private final Clock reloj;

    public IncidenciaInventarioService(IncidenciaInventarioRepository incidencias, PersonalTiendaRepository personal,
                                       CajaSesionRepository turnos, CodigosSucursal codigosSucursal,
                                       CatalogoService catalogo, OutboxPublicador outbox, Clock reloj) {
        this.incidencias = incidencias;
        this.personal = personal;
        this.turnos = turnos;
        this.codigosSucursal = codigosSucursal;
        this.catalogo = catalogo;
        this.outbox = outbox;
        this.reloj = reloj;
    }

    /**
     * Registra la incidencia (RF-32).
     *
     * <p>Errores: {@code 400 PARAMETRO_INVALIDO} si la observación tiene menos de 5 caracteres;
     * {@code 403} sin perfil activo; {@code 409 TURNO_NO_ABIERTO} sin turno de caja abierto en la tienda;
     * {@code 409 IDEMPOTENCY_CONFLICT} si la misma clave llega con otro cuerpo.</p>
     *
     * @param usuarioId      {@code sub} del token del vendedor
     * @param idempotencyKey cabecera {@code Idempotency-Key}; un reintento devuelve la misma incidencia
     */
    @Transactional
    public IncidenciaResponse registrar(UUID usuarioId, String idempotencyKey, RegistroIncidenciaRequest peticion) {
        PersonalTienda vendedor = vendedorActivo(usuarioId);

        var repetida = incidencias.findByIdempotencyKey(idempotencyKey);
        if (repetida.isPresent()) {
            return repetir(repetida.get(), peticion);
        }

        String observacion = peticion.detalleObservacion() == null ? "" : peticion.detalleObservacion().trim();
        if (observacion.length() < MIN_OBSERVACION) {
            throw new ParametroInvalidoException("PARAMETRO_INVALIDO", "detalleObservacion",
                    "La observación debe tener al menos " + MIN_OBSERVACION + " caracteres");
        }

        turnos.findFirstByTiendaIdAndEstadoOrderByFechaHoraAperturaDesc(vendedor.getTiendaId(), EstadoCajaSesion.ABIERTA)
                .orElseThrow(() -> new ConflictoException("TURNO_NO_ABIERTO",
                        "Abre el turno de caja antes de reportar una incidencia"));

        String sku = peticion.sku().trim();
        String tienda = codigosSucursal.codigoDe(vendedor.getTiendaId());
        OffsetDateTime ahora = OffsetDateTime.now(reloj).withOffsetSameInstant(ZoneOffset.UTC);
        UUID incidenciaId = UUID.randomUUID();

        IncidenciaInventario incidencia = incidencias.save(IncidenciaInventario.builder()
                .idIncidencia(incidenciaId)
                .tiendaId(tienda)
                .vendedorReportaId(usuarioId)
                .sku(sku)
                .nombreProducto(nombreDe(sku))
                .codigoBarras(vacioANulo(peticion.codigoBarras()) == null ? sku : peticion.codigoBarras().trim())
                .tipoFalla(peticion.tipoFalla())
                .detalleObservacion(observacion)
                .evidenciaFotoUrl(vacioANulo(peticion.evidenciaFotoUrl()))
                .estado(EstadoIncidencia.PENDIENTE_REPORTE)
                .idempotencyKey(idempotencyKey)
                .fechaReporte(ahora)
                .build());

        outbox.registrar(TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA, incidenciaId.toString(),
                IncidenciaReportadaPayload.desdeRetail(incidenciaId, tienda, sku, peticion.tipoFalla().name(),
                        ahora, usuarioId));

        log.info("F12: incidencia {} registrada (sku={}, tienda={}, tipo={}) en PENDIENTE_REPORTE",
                incidenciaId, sku, tienda, peticion.tipoFalla());
        return IncidenciaResponse.de(incidencia);
    }

    /** Mismo reporte reenviado: devuelve la incidencia original; con otro cuerpo, {@code 409}. */
    private IncidenciaResponse repetir(IncidenciaInventario original, RegistroIncidenciaRequest peticion) {
        boolean mismoCuerpo = Objects.equals(original.getSku(), peticion.sku() == null ? null : peticion.sku().trim())
                && original.getTipoFalla() == peticion.tipoFalla()
                && Objects.equals(original.getDetalleObservacion(),
                peticion.detalleObservacion() == null ? null : peticion.detalleObservacion().trim());
        if (!mismoCuerpo) {
            throw new ConflictoException("IDEMPOTENCY_CONFLICT",
                    "La Idempotency-Key ya se usó para reportar otra incidencia");
        }
        return IncidenciaResponse.de(original);
    }

    private PersonalTienda vendedorActivo(UUID usuarioId) {
        PersonalTienda vendedor = personal.findByUsuarioIdAndActivoTrue(usuarioId)
                .orElseThrow(() -> new AccesoDenegadoException("FORBIDDEN_USUARIO_SIN_TIENDA",
                        "El usuario autenticado no tiene un perfil activo en la tienda"));
        if (vendedor.getPerfilTienda() == PerfilTienda.CAJERO) {
            throw new AccesoDenegadoException("ROL_NO_PERMITIDO", "El perfil no puede reportar incidencias");
        }
        return vendedor;
    }

    /** El nombre es solo para la bandeja: si G6 no responde, el reporte no se bloquea. */
    private String nombreDe(String sku) {
        try {
            return catalogo.nombreDelSku(sku)
                    .map(nombre -> nombre.length() > MAX_NOMBRE ? nombre.substring(0, MAX_NOMBRE) : nombre)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("F12: no se pudo obtener el nombre del SKU {} desde Productos: {}", sku, e.getMessage());
            return null;
        }
    }

    private static String vacioANulo(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }
}
