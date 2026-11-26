package pe.edu.unmsm.fisi.retail.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Evento pendiente de notificar a otro módulo: patrón <em>transactional outbox</em> (contrato v0.5, §2.9).
 *
 * <p>El servicio de negocio lo guarda en la <strong>misma transacción</strong> que su propio cambio.
 * Un job lo envía después al destino, con reintentos y espera creciente. Así el vendedor no espera
 * a otro módulo y ninguna notificación se pierde si ese módulo está caído.</p>
 */
@Entity
@Table(name = "RET_OUTBOX_EVENTO", indexes = {
        @Index(name = "idx_outbox_pendientes", columnList = "estado, proximo_intento"),
        @Index(name = "ux_outbox_tipo_agregado", columnList = "tipo_evento, agregado_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvento {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tipo_evento", nullable = false, length = 60)
    private String tipoEvento;

    /** Id del recurso de Retail que originó el evento; también es la {@code Idempotency-Key}. */
    @Column(name = "agregado_id", nullable = false, length = 100)
    private String agregadoId;

    @Column(name = "destino", nullable = false, length = 30)
    private String destino;

    /** Cuerpo JSON exacto que se envía al destino. */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    private EstadoOutbox estado;

    @Column(name = "intentos", nullable = false)
    private int intentos;

    @Column(name = "proximo_intento", nullable = false)
    private OffsetDateTime proximoIntento;

    @Column(name = "ultimo_error", columnDefinition = "text")
    private String ultimoError;

    /** Id que devolvió el destino al aceptar el evento (por ejemplo, {@code incidenciaInventoryId}). */
    @Column(name = "referencia_externa", length = 100)
    private String referenciaExterna;

    /** {@code X-Correlation-Id} de la petición que originó el evento, para seguirlo entre módulos. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "creado_en", nullable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "enviado_en")
    private OffsetDateTime enviadoEn;
}
