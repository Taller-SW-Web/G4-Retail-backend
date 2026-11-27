package pe.edu.unmsm.fisi.retail.service.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import pe.edu.unmsm.fisi.retail.model.EstadoOutbox;
import pe.edu.unmsm.fisi.retail.model.OutboxEvento;
import pe.edu.unmsm.fisi.retail.repository.OutboxEventoRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Política de reintentos del outbox (contrato v0.5, §2.9): espera creciente, tope, rechazo
 * definitivo y agotamiento de intentos.
 */
@DisplayName("OutboxDespachador — reintentos con espera creciente")
class OutboxDespachadorTest {

    private static final Instant AHORA = Instant.parse("2026-11-26T15:00:00Z");
    private static final Clock RELOJ = Clock.fixed(AHORA, ZoneOffset.UTC);

    private OutboxEventoRepository repositorio;
    private ApplicationEventPublisher publicador;
    private DestinoFalso destino;
    private OutboxDespachador despachador;
    private OutboxEvento evento;

    @BeforeEach
    void preparar() {
        repositorio = mock(OutboxEventoRepository.class);
        publicador = mock(ApplicationEventPublisher.class);
        destino = new DestinoFalso();
        despachador = new OutboxDespachador(repositorio, List.of(destino), publicador, RELOJ,
                20, 3, 30_000, 3_600_000);
        evento = pendiente();
        when(repositorio.listosParaEnviar(eq(EstadoOutbox.PENDIENTE), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(evento));
    }

    @Test
    @DisplayName("Un 503 suma un intento, guarda el error y reprograma a 30 s")
    void errorTransitorioReprograma() {
        destino.fallos.add(EnvioOutboxException.porEstadoHttp(503, "Service Unavailable"));

        assertThat(despachador.procesarPendientes()).isZero();

        assertThat(evento.getEstado()).isEqualTo(EstadoOutbox.PENDIENTE);
        assertThat(evento.getIntentos()).isEqualTo(1);
        assertThat(evento.getUltimoError()).contains("503");
        assertThat(evento.getProximoIntento()).isEqualTo(OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC).plusSeconds(30));
        verify(publicador, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("Un 422 es rechazo definitivo: pasa a FALLIDO sin reintentar y avisa con EventoOutboxFallido")
    void rechazoDefinitivoFalla() {
        destino.fallos.add(EnvioOutboxException.porEstadoHttp(422, "SKU desconocido"));

        despachador.procesarPendientes();

        assertThat(evento.getEstado()).isEqualTo(EstadoOutbox.FALLIDO);
        assertThat(evento.getIntentos()).isEqualTo(1);
        verify(publicador).publishEvent(any(EventoOutboxFallido.class));
    }

    @Test
    @DisplayName("Al agotar los intentos un error transitorio también pasa a FALLIDO")
    void agotaIntentos() {
        evento.setIntentos(2);
        destino.fallos.add(EnvioOutboxException.transitorio("timeout"));

        despachador.procesarPendientes();

        assertThat(evento.getEstado()).isEqualTo(EstadoOutbox.FALLIDO);
        assertThat(evento.getIntentos()).isEqualTo(3);
    }

    @Test
    @DisplayName("Tras un fallo transitorio, el siguiente envío exitoso lo deja ENVIADO con su referencia")
    void reintentoExitoso() {
        destino.fallos.add(EnvioOutboxException.transitorio("timeout"));
        despachador.procesarPendientes();

        destino.referencia = "INC-INV-00042";
        assertThat(despachador.procesarPendientes()).isEqualTo(1);

        assertThat(evento.getEstado()).isEqualTo(EstadoOutbox.ENVIADO);
        assertThat(evento.getIntentos()).isEqualTo(2);
        assertThat(evento.getReferenciaExterna()).isEqualTo("INC-INV-00042");
        assertThat(evento.getUltimoError()).isNull();
        assertThat(destino.clavesRecibidas).containsOnly(evento.getAgregadoId());
        verify(publicador).publishEvent(any(EventoOutboxEnviado.class));
    }

    @Test
    @DisplayName("La espera se duplica en cada intento y no pasa de 1 hora")
    void esperaCreciente() {
        assertThat(despachador.esperaPara(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(despachador.esperaPara(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(despachador.esperaPara(3)).isEqualTo(Duration.ofSeconds(120));
        assertThat(despachador.esperaPara(9)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("Un 429 y un 408 se clasifican como transitorios; un 404 como definitivo")
    void clasificacionHttp() {
        assertThat(EnvioOutboxException.porEstadoHttp(429, "").esDefinitivo()).isFalse();
        assertThat(EnvioOutboxException.porEstadoHttp(408, "").esDefinitivo()).isFalse();
        assertThat(EnvioOutboxException.porEstadoHttp(404, "").esDefinitivo()).isTrue();
    }

    private static OutboxEvento pendiente() {
        OutboxEvento e = new OutboxEvento();
        e.setId(UUID.randomUUID());
        e.setTipoEvento(TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA.name());
        e.setAgregadoId(UUID.randomUUID().toString());
        e.setDestino(DestinoOutbox.PRODUCTOS_G6);
        e.setPayload("{}");
        e.setEstado(EstadoOutbox.PENDIENTE);
        e.setProximoIntento(OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC));
        e.setCreadoEn(OffsetDateTime.ofInstant(AHORA, ZoneOffset.UTC));
        return e;
    }

    /** Destino que falla con las excepciones encoladas y luego acepta. */
    static class DestinoFalso implements DestinoOutbox {
        final List<RuntimeException> fallos = new ArrayList<>();
        final List<String> clavesRecibidas = new ArrayList<>();
        String referencia = "INC-INV-00001";

        @Override public String nombre() { return PRODUCTOS_G6; }

        @Override
        public String enviar(OutboxEvento evento) {
            clavesRecibidas.add(evento.getAgregadoId());
            if (!fallos.isEmpty()) {
                throw fallos.remove(0);
            }
            return referencia;
        }

        @Override public String origen() { return "FALSO"; }
    }
}
