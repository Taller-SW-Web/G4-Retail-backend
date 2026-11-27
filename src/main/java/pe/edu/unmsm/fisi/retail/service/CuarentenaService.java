package pe.edu.unmsm.fisi.retail.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unmsm.fisi.retail.dto.CuarentenaItemResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.model.EstadoIncidencia;
import pe.edu.unmsm.fisi.retail.repository.IncidenciaInventarioRepository;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;
import pe.edu.unmsm.fisi.retail.service.outbox.EventoOutboxEnviado;
import pe.edu.unmsm.fisi.retail.service.outbox.EventoOutboxFallido;
import pe.edu.unmsm.fisi.retail.service.outbox.TipoEventoOutbox;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cuarentena de prendas en la tienda (F12, RF-33; contrato §5.12).
 *
 * <ul>
 *   <li>Bandeja de la tienda: {@code GET /api/v1/retail/inventario/cuarentena}.</li>
 *   <li>SKU bloqueados para la venta: los usan el catálogo ({@code enCuarentena}) y el carrito
 *   ({@code 422 SKU_EN_CUARENTENA}).</li>
 *   <li>Confirmación de Inventory (G6): al enviarse el evento del outbox, la incidencia pasa de
 *   {@code PENDIENTE_REPORTE} a {@code EN_CUARENTENA} con su {@code incidenciaInventoryId}.</li>
 * </ul>
 *
 * <p>La cuarentena activa son {@code PENDIENTE_REPORTE} y {@code EN_CUARENTENA}: la prenda queda
 * bloqueada desde que se reporta, aunque G6 todavía no responda.</p>
 */
@Slf4j
@Service
public class CuarentenaService {

    private final IncidenciaInventarioRepository incidencias;
    private final PersonalTiendaRepository personal;
    private final CodigosSucursal codigosSucursal;

    public CuarentenaService(IncidenciaInventarioRepository incidencias, PersonalTiendaRepository personal,
                             CodigosSucursal codigosSucursal) {
        this.incidencias = incidencias;
        this.personal = personal;
        this.codigosSucursal = codigosSucursal;
    }

    /** Bandeja de cuarentena de la tienda del vendedor (CA-RF33-03). */
    @Transactional(readOnly = true)
    public List<CuarentenaItemResponse> listar(UUID usuarioId) {
        String tienda = tiendaDe(usuarioId).orElseThrow(() -> new AccesoDenegadoException(
                "FORBIDDEN_USUARIO_SIN_TIENDA", "El usuario autenticado no tiene un perfil activo en la tienda"));
        return incidencias.findByTiendaIdAndEstadoInOrderByFechaReporteDesc(tienda, EstadoIncidencia.CUARENTENA_ACTIVA)
                .stream()
                .map(CuarentenaItemResponse::de)
                .toList();
    }

    /**
     * De los SKU dados, los que están en cuarentena activa en la tienda del vendedor (RF-33).
     *
     * @return vacío si no hay usuario o no tiene perfil: sin tienda no hay cuarentena que aplicar
     */
    @Transactional(readOnly = true)
    public Set<String> skusBloqueados(UUID usuarioId, Collection<String> skus) {
        if (usuarioId == null || skus == null || skus.isEmpty()) {
            return Set.of();
        }
        return tiendaDe(usuarioId)
                .map(tienda -> (Set<String>) new HashSet<>(
                        incidencias.skusConEstado(tienda, skus, EstadoIncidencia.CUARENTENA_ACTIVA)))
                .orElse(Set.of());
    }

    /**
     * Inventory aceptó el reporte (contrato §2.9 y §5.12): la incidencia pasa a {@code EN_CUARENTENA}.
     *
     * <p>Nunca propaga un error: el despachador del outbox ya marcó el evento como enviado y un fallo
     * aquí no debe hacerlo reintentar.</p>
     */
    @EventListener
    @Transactional
    public void alEnviarseAInventory(EventoOutboxEnviado evento) {
        if (evento.tipo() != TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA) {
            return;
        }
        try {
            incidencias.findById(UUID.fromString(evento.agregadoId())).ifPresentOrElse(incidencia -> {
                if (incidencia.getEstado() == EstadoIncidencia.PENDIENTE_REPORTE) {
                    incidencia.setEstado(EstadoIncidencia.EN_CUARENTENA);
                }
                incidencia.setIncidenciaInventoryId(evento.referenciaExterna());
                incidencias.save(incidencia);
                log.info("F12: incidencia {} aceptada por Inventory ({}), estado {}",
                        incidencia.getIdIncidencia(), evento.referenciaExterna(), incidencia.getEstado());
            }, () -> log.warn("F12: Inventory aceptó la incidencia {}, pero no existe en Retail", evento.agregadoId()));
        } catch (RuntimeException e) {
            log.error("F12: no se pudo confirmar la incidencia {} tras el envío a Inventory: {}",
                    evento.agregadoId(), e.getMessage());
        }
    }

    /**
     * Inventory rechazó el reporte de forma definitiva o se agotaron los intentos. La incidencia sigue
     * en {@code PENDIENTE_REPORTE}, bloqueando la venta, hasta que se revise el evento {@code FALLIDO}.
     */
    @EventListener
    public void alFallarEnvioAInventory(EventoOutboxFallido evento) {
        if (evento.tipo() == TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA) {
            log.error("F12: el reporte de la incidencia {} a Inventory quedó FALLIDO tras {} intentos: {}",
                    evento.agregadoId(), evento.intentos(), evento.motivo());
        }
    }

    /** Tienda del perfil como código de sucursal ({@code TIENDA-...}), igual que en la incidencia. */
    Optional<String> tiendaDe(UUID usuarioId) {
        return personal.findByUsuarioIdAndActivoTrue(usuarioId)
                .map(perfil -> codigosSucursal.codigoDe(perfil.getTiendaId()));
    }
}
