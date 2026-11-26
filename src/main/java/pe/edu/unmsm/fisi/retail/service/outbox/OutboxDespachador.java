package pe.edu.unmsm.fisi.retail.service.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import pe.edu.unmsm.fisi.retail.model.EstadoOutbox;
import pe.edu.unmsm.fisi.retail.model.OutboxEvento;
import pe.edu.unmsm.fisi.retail.repository.OutboxEventoRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Envía los eventos pendientes del outbox y aplica la política de reintentos (contrato v0.5, §2.9).
 *
 * <ul>
 *   <li>{@code 2xx}: {@code ENVIADO}, guarda la referencia externa y publica {@link EventoOutboxEnviado}.</li>
 *   <li>Error transitorio: suma un intento y reprograma con espera {@code base × 2^(intentos−1)},
 *   con un tope.</li>
 *   <li>Rechazo definitivo o intentos agotados: {@code FALLIDO} y publica {@link EventoOutboxFallido}.</li>
 * </ul>
 *
 * <p>Cada evento se guarda por separado: el fallo de uno no detiene al resto del lote.</p>
 */
@Slf4j
@Service
public class OutboxDespachador {

    private static final int LARGO_MAXIMO_ERROR = 1000;

    private final OutboxEventoRepository repositorio;
    private final Map<String, DestinoOutbox> destinos;
    private final ApplicationEventPublisher eventos;
    private final Clock reloj;
    private final int tamanoLote;
    private final int maxIntentos;
    private final Duration esperaBase;
    private final Duration esperaMaxima;

    public OutboxDespachador(OutboxEventoRepository repositorio,
                             List<DestinoOutbox> destinos,
                             ApplicationEventPublisher eventos,
                             Clock reloj,
                             @Value("${retail.outbox.tamano-lote:20}") int tamanoLote,
                             @Value("${retail.outbox.max-intentos:10}") int maxIntentos,
                             @Value("${retail.outbox.espera-base-ms:30000}") long esperaBaseMs,
                             @Value("${retail.outbox.espera-maxima-ms:3600000}") long esperaMaximaMs) {
        this.repositorio = repositorio;
        this.destinos = destinos.stream().collect(Collectors.toMap(DestinoOutbox::nombre, Function.identity()));
        this.eventos = eventos;
        this.reloj = reloj;
        this.tamanoLote = tamanoLote;
        this.maxIntentos = maxIntentos;
        this.esperaBase = Duration.ofMillis(esperaBaseMs);
        this.esperaMaxima = Duration.ofMillis(esperaMaximaMs);
    }

    /**
     * Procesa un lote de eventos listos para enviarse.
     *
     * @return cuántos eventos quedaron {@code ENVIADO} en esta pasada
     */
    public int procesarPendientes() {
        List<OutboxEvento> lote = repositorio.listosParaEnviar(EstadoOutbox.PENDIENTE,
                OffsetDateTime.now(reloj), PageRequest.of(0, tamanoLote));
        int enviados = 0;
        for (OutboxEvento evento : lote) {
            if (procesar(evento)) {
                enviados++;
            }
        }
        if (!lote.isEmpty()) {
            log.info("Outbox: {} de {} eventos enviados en esta pasada", enviados, lote.size());
        }
        return enviados;
    }

    private boolean procesar(OutboxEvento evento) {
        DestinoOutbox destino = destinos.get(evento.getDestino());
        if (destino == null) {
            fallar(evento, "No hay adaptador configurado para el destino " + evento.getDestino());
            return false;
        }
        evento.setIntentos(evento.getIntentos() + 1);
        try {
            String referencia = destino.enviar(evento);
            marcarEnviado(evento, referencia, destino.origen());
            return true;
        } catch (EnvioOutboxException e) {
            registrarFallo(evento, e.getMessage(), e.esDefinitivo());
        } catch (RuntimeException e) {
            // Un fallo no previsto (por ejemplo, al pedir el token de servicio) se trata como transitorio.
            registrarFallo(evento, e.getMessage(), false);
        }
        return false;
    }

    private void registrarFallo(OutboxEvento evento, String motivo, boolean definitivo) {
        if (definitivo || evento.getIntentos() >= maxIntentos) {
            fallar(evento, motivo);
        } else {
            reprogramar(evento, motivo);
        }
    }

    private void marcarEnviado(OutboxEvento evento, String referencia, String origen) {
        evento.setEstado(EstadoOutbox.ENVIADO);
        evento.setReferenciaExterna(referencia);
        evento.setEnviadoEn(OffsetDateTime.now(reloj));
        evento.setUltimoError(null);
        repositorio.save(evento);
        log.info("Outbox: evento {} ({}, agregado={}) ENVIADO a {} en el intento {} (referencia={}, correlationId={})",
                evento.getId(), evento.getTipoEvento(), evento.getAgregadoId(), origen, evento.getIntentos(),
                referencia, evento.getCorrelationId());
        tipo(evento).ifPresent(t -> eventos.publishEvent(
                new EventoOutboxEnviado(t, evento.getAgregadoId(), referencia)));
    }

    private void reprogramar(OutboxEvento evento, String motivo) {
        Duration espera = esperaPara(evento.getIntentos());
        evento.setProximoIntento(OffsetDateTime.now(reloj).plus(espera));
        evento.setUltimoError(recortar(motivo));
        repositorio.save(evento);
        log.warn("Outbox: evento {} ({}) falló en el intento {}; se reintenta en {} s: {}",
                evento.getId(), evento.getTipoEvento(), evento.getIntentos(), espera.toSeconds(), motivo);
    }

    private void fallar(OutboxEvento evento, String motivo) {
        evento.setEstado(EstadoOutbox.FALLIDO);
        evento.setUltimoError(recortar(motivo));
        repositorio.save(evento);
        log.error("Outbox: evento {} ({}, agregado={}) FALLIDO tras {} intentos: {}",
                evento.getId(), evento.getTipoEvento(), evento.getAgregadoId(), evento.getIntentos(), motivo);
        tipo(evento).ifPresent(t -> eventos.publishEvent(
                new EventoOutboxFallido(t, evento.getAgregadoId(), evento.getIntentos(), motivo)));
    }

    /** Espera antes del siguiente intento: {@code base × 2^(intentos−1)}, sin pasar del tope. */
    Duration esperaPara(int intentos) {
        int exponente = Math.max(0, Math.min(intentos - 1, 20));
        Duration espera = esperaBase.multipliedBy(1L << exponente);
        return espera.compareTo(esperaMaxima) > 0 ? esperaMaxima : espera;
    }

    private static Optional<TipoEventoOutbox> tipo(OutboxEvento evento) {
        try {
            return Optional.of(TipoEventoOutbox.valueOf(evento.getTipoEvento()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String recortar(String texto) {
        if (texto == null) {
            return null;
        }
        return texto.length() <= LARGO_MAXIMO_ERROR ? texto : texto.substring(0, LARGO_MAXIMO_ERROR);
    }
}
