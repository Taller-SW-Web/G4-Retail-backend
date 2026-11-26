package pe.edu.unmsm.fisi.retail.service.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import pe.edu.unmsm.fisi.retail.integracion.inventario.IncidenciaReportadaPayload;
import pe.edu.unmsm.fisi.retail.model.EstadoOutbox;
import pe.edu.unmsm.fisi.retail.model.OutboxEvento;
import pe.edu.unmsm.fisi.retail.repository.OutboxEventoRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flujo asíncrono de F12 hacia Inventory (contrato v0.5, §2.9 y §5.12) de punta a punta, con la base
 * H2 y el doble de Inventory en memoria. El job programado está apagado: la prueba llama al
 * despachador como lo haría el job.
 */
@SpringBootTest(properties = {
        "retail.outbox.habilitado=false",
        "retail.integracion.inventario.modo=memoria"
})
@ActiveProfiles({"local", "mock"})
@Import(OutboxIncidenciasIntegrationTest.Escucha.class)
@DisplayName("Outbox de incidencias F12 → Inventory (G6)")
class OutboxIncidenciasIntegrationTest {

    @Autowired private OutboxPublicador publicador;
    @Autowired private OutboxDespachador despachador;
    @Autowired private OutboxEventoRepository repositorio;
    @Autowired private TransactionTemplate transaccion;
    @Autowired private Escucha escucha;

    @BeforeEach
    void limpiar() {
        repositorio.deleteAll();
        escucha.enviados.clear();
    }

    @Test
    @DisplayName("§2.9: la incidencia registrada en una transacción queda PENDIENTE y el job la pasa a ENVIADO")
    void registraYEnvia() {
        String incidenciaId = UUID.randomUUID().toString();

        transaccion.executeWithoutResult(estado ->
                publicador.registrar(TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA, incidenciaId, payload(incidenciaId)));

        OutboxEvento pendiente = repositorio.findByTipoEventoAndAgregadoId(
                TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA.name(), incidenciaId).orElseThrow();
        assertThat(pendiente.getEstado()).isEqualTo(EstadoOutbox.PENDIENTE);
        assertThat(pendiente.getDestino()).isEqualTo(DestinoOutbox.PRODUCTOS_G6);
        assertThat(pendiente.getPayload()).contains("\"external_incident_id\":\"" + incidenciaId + "\"")
                .contains("\"type\":\"DANIO\"").contains("\"location_id\":\"TIENDA-MIRAFLORES\"");

        int enviados = despachador.procesarPendientes();

        OutboxEvento enviado = repositorio.findById(pendiente.getId()).orElseThrow();
        assertThat(enviados).isEqualTo(1);
        assertThat(enviado.getEstado()).isEqualTo(EstadoOutbox.ENVIADO);
        assertThat(enviado.getIntentos()).isEqualTo(1);
        assertThat(enviado.getReferenciaExterna()).startsWith("INC-INV-");
        assertThat(enviado.getEnviadoEn()).isNotNull();
    }

    @Test
    @DisplayName("§2.9: al enviarse publica EventoOutboxEnviado con el incidenciaInventoryId para el dueño del agregado")
    void publicaEventoAlEnviar() {
        String incidenciaId = UUID.randomUUID().toString();
        transaccion.executeWithoutResult(estado ->
                publicador.registrar(TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA, incidenciaId, payload(incidenciaId)));

        despachador.procesarPendientes();

        assertThat(escucha.enviados).hasSize(1);
        EventoOutboxEnviado evento = escucha.enviados.get(0);
        assertThat(evento.tipo()).isEqualTo(TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA);
        assertThat(evento.agregadoId()).isEqualTo(incidenciaId);
        assertThat(evento.referenciaExterna()).startsWith("INC-INV-");
    }

    @Test
    @DisplayName("§2.9: si la transacción de negocio se revierte, el evento tampoco existe")
    void rollbackNoDejaEvento() {
        String incidenciaId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> transaccion.executeWithoutResult(estado -> {
            publicador.registrar(TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA, incidenciaId, payload(incidenciaId));
            throw new IllegalStateException("falla el guardado de la incidencia");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(repositorio.findByTipoEventoAndAgregadoId(
                TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA.name(), incidenciaId)).isEmpty();
    }

    @Test
    @DisplayName("§2.9: registrar fuera de una transacción falla en vez de dejar un evento huérfano")
    void exigeTransaccion() {
        assertThatThrownBy(() -> publicador.registrar(TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA,
                UUID.randomUUID().toString(), "{}"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("§2.9: registrar dos veces la misma incidencia no duplica el evento")
    void registroIdempotente() {
        String incidenciaId = UUID.randomUUID().toString();
        transaccion.executeWithoutResult(estado -> {
            publicador.registrar(TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA, incidenciaId, payload(incidenciaId));
            publicador.registrar(TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA, incidenciaId, payload(incidenciaId));
        });

        assertThat(repositorio.count()).isEqualTo(1);
    }

    private static IncidenciaReportadaPayload payload(String incidenciaId) {
        return IncidenciaReportadaPayload.desdeRetail(UUID.fromString(incidenciaId), "TIENDA-MIRAFLORES",
                "CAM-PERU-M-BLA", "MANCHADO_PROBADOR", OffsetDateTime.parse("2026-11-26T15:30:00Z"), null);
    }

    /** Escucha los eventos internos que el despachador publica, como lo hará el servicio de F12. */
    @TestConfiguration
    static class Escucha {
        final List<EventoOutboxEnviado> enviados = new CopyOnWriteArrayList<>();

        @EventListener
        void alEnviar(EventoOutboxEnviado evento) {
            enviados.add(evento);
        }
    }
}
