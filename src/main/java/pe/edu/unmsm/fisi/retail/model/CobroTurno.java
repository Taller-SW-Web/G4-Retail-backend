package pe.edu.unmsm.fisi.retail.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Cobro de una venta asociado a un turno de caja. Tabla {@code RET_COBRO_TURNO}.
 *
 * <p>Es un <strong>registro de caja</strong>, no la venta: Retail no guarda pedidos ni pagos, eso
 * vive en Ventas (G5). Aqui solo queda lo que hace falta para el arqueo ciego: cuanto entro a la
 * gaveta en este turno, con el identificador del pedido de G5 como referencia.</p>
 *
 * <p>La {@code idempotencyKey} es unica: reintentar una venta con la misma llave no crea otro cobro.</p>
 */
@Entity
@Table(name = "RET_COBRO_TURNO")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CobroTurno {

    public static final String EFECTIVO = "EFECTIVO";
    public static final String TARJETA_POS = "TARJETA_POS";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id_cobro", nullable = false, updatable = false)
    private UUID idCobro;

    /** Turno en el que se cobro. La tabla lo exige (ON DELETE RESTRICT). */
    @Column(name = "caja_sesion_id", nullable = false)
    private UUID cajaSesionId;

    /** Misma {@code Idempotency-Key} que viaja a Ventas (G5). Unica en la tabla. */
    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false, length = 64)
    private String idempotencyKey;

    /** Pedido de G5 ({@code PED-AAAA-NNNNN}) o el de contingencia local; vacio mientras esta pendiente. */
    @Column(name = "pedido_id", length = 40)
    private String pedidoId;

    @Column(name = "comprobante_serie", length = 4)
    private String comprobanteSerie;

    @Column(name = "comprobante_numero", length = 20)
    private String comprobanteNumero;

    /** {@code EFECTIVO} o {@code TARJETA_POS}. */
    @Column(name = "medio_pago", nullable = false, length = 20)
    private String medioPago;

    /** Total de la venta. Es lo que se suma al efectivo de la gaveta (el vuelto ya salio). */
    @Column(name = "monto_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal montoTotal;

    @Column(name = "monto_recibido", nullable = false, precision = 12, scale = 2)
    private BigDecimal montoRecibido;

    @Column(name = "vuelto", nullable = false, precision = 12, scale = 2)
    private BigDecimal vuelto;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 30)
    private EstadoCobroTurno estado;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void alCrear() {
        OffsetDateTime ahora = OffsetDateTime.now();
        this.createdAt = ahora;
        this.updatedAt = ahora;
        if (this.estado == null) {
            this.estado = EstadoCobroTurno.PENDIENTE_CONFIRMACION;
        }
    }

    @PreUpdate
    void alActualizar() {
        this.updatedAt = OffsetDateTime.now();
    }
}
