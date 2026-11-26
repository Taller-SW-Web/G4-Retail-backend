package pe.edu.unmsm.fisi.retail.service.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unmsm.fisi.retail.model.EstadoOutbox;
import pe.edu.unmsm.fisi.retail.model.OutboxEvento;
import pe.edu.unmsm.fisi.retail.repository.OutboxEventoRepository;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Deja un evento en el outbox para que el job lo envíe después (contrato v0.5, §2.9).
 *
 * <p>Exige una transacción abierta ({@link Propagation#MANDATORY}): el evento se confirma solo si se
 * confirma el cambio de negocio que lo originó. Un servicio que lo llame fuera de una transacción
 * falla de inmediato, en vez de dejar un evento huérfano.</p>
 *
 * <p>Uso desde F12: {@code outbox.registrar(INCIDENCIA_INVENTARIO_REPORTADA, incidenciaId, payload)}
 * dentro del mismo método {@code @Transactional} que guarda la incidencia.</p>
 */
@Slf4j
@Service
public class OutboxPublicador {

    /** Clave de MDC donde el filtro de trazabilidad deja el {@code X-Correlation-Id}. */
    public static final String MDC_CORRELACION = "correlationId";

    private final OutboxEventoRepository repositorio;
    private final ObjectMapper mapper;
    private final Clock reloj;

    public OutboxPublicador(OutboxEventoRepository repositorio, ObjectMapper mapper, Clock reloj) {
        this.repositorio = repositorio;
        this.mapper = mapper;
        this.reloj = reloj;
    }

    /**
     * Registra el evento en estado {@code PENDIENTE}, listo para enviarse ya.
     *
     * <p>Es idempotente por {@code (tipo, agregadoId)}: si el evento ya existe, lo devuelve sin
     * duplicarlo.</p>
     *
     * @param agregadoId id del recurso de Retail; es la {@code Idempotency-Key} hacia el destino
     * @param payload    objeto que se serializa a JSON tal cual se enviará (o el JSON ya armado)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvento registrar(TipoEventoOutbox tipo, String agregadoId, Object payload) {
        return repositorio.findByTipoEventoAndAgregadoId(tipo.name(), agregadoId)
                .orElseGet(() -> nuevo(tipo, agregadoId, payload));
    }

    private OutboxEvento nuevo(TipoEventoOutbox tipo, String agregadoId, Object payload) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        OutboxEvento evento = new OutboxEvento();
        evento.setId(UUID.randomUUID());
        evento.setTipoEvento(tipo.name());
        evento.setAgregadoId(agregadoId);
        evento.setDestino(tipo.destino());
        evento.setPayload(json(payload));
        evento.setEstado(EstadoOutbox.PENDIENTE);
        evento.setIntentos(0);
        evento.setProximoIntento(ahora);
        evento.setCorrelationId(MDC.get(MDC_CORRELACION));
        evento.setCreadoEn(ahora);
        OutboxEvento guardado = repositorio.save(evento);
        log.info("Outbox: evento {} registrado para {} (agregado={}, destino={})",
                guardado.getId(), tipo, agregadoId, tipo.destino());
        return guardado;
    }

    private String json(Object payload) {
        if (payload instanceof String texto) {
            return texto;
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("El payload del evento no se puede convertir a JSON", e);
        }
    }
}
