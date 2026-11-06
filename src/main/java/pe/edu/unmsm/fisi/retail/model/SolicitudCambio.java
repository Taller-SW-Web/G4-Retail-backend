package pe.edu.unmsm.fisi.retail.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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
 * Cambio de prenda registrado en mostrador, con su inspeccion fisica (tabla
 * {@code RET_SOLICITUD_CAMBIO_MOSTRADOR}, F9).
 *
 * <p>Es la evidencia de auditoria del cambio: que boleta se uso, que prenda volvio, en que estado y
 * que vendedor la inspecciono. Ninguna columna toca stock: Retail no mueve saldos de inventario
 * (G4-P0-03).</p>
 *
 * <p>El mapeo sigue la propuesta de esquema enviada a la DBA para la Semana 11. Si el DDL final
 * cambia un nombre o un tipo, esta clase debe cambiar igual: en la nube el backend arranca con
 * {@code ddl-auto=validate}.</p>
 */
@Entity
@Table(name = "RET_SOLICITUD_CAMBIO_MOSTRADOR")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SolicitudCambio {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id_solicitud", nullable = false, updatable = false)
    private UUID idSolicitud;

    /** Turno de caja abierto en el que se registro el cambio. */
    @Column(name = "caja_sesion_id", nullable = false)
    private UUID cajaSesionId;

    /** Vendedor que hizo la inspeccion (RF-24: el registro guarda al auditor). */
    @Column(name = "vendedor_id", nullable = false)
    private UUID vendedorId;

    /** Comprobante con el que se compro la prenda, por ejemplo {@code B001-00000045}. */
    @Column(name = "boleta_numero", nullable = false, length = 20)
    private String boletaNumero;

    /** Pedido central en Ventas (G5), si se conoce. */
    @Column(name = "pedido_id_origen", length = 20)
    private String pedidoIdOrigen;

    /** SKU de la prenda devuelta. */
    @Column(name = "variante_sku_devuelta_id", nullable = false, length = 40)
    private String varianteSku;

    /** Puede ser nulo: en mostrador no siempre se identifica al cliente. */
    @Column(name = "cliente_id")
    private UUID clienteId;

    @Column(name = "cantidad", nullable = false)
    private int cantidad;

    @Enumerated(EnumType.STRING)
    @Column(name = "motivo_cambio", nullable = false, length = 50)
    private MotivoCambio motivoCambio;

    @Column(name = "inspeccion_etiquetas", nullable = false)
    private boolean inspeccionEtiquetas;

    @Column(name = "inspeccion_sin_uso", nullable = false)
    private boolean inspeccionSinUso;

    @Column(name = "inspeccion_empaque", nullable = false)
    private boolean inspeccionEmpaque;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_aprobacion", nullable = false, length = 30)
    private EstadoAprobacionCambio estadoAprobacion;

    /** Codigo del vale emitido como compensacion. */
    @Column(name = "vale_temporal_codigo", length = 50)
    private String valeTemporalCodigo;

    @Column(name = "monto_acreditado", nullable = false, precision = 12, scale = 2)
    private BigDecimal montoAcreditado;

    /**
     * Clave de idempotencia de la peticion. Es unica: si el vendedor pulsa dos veces o la red reintenta,
     * no se emiten dos vales por la misma prenda.
     */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(name = "fecha_inspeccion", nullable = false)
    private OffsetDateTime fechaInspeccion;

    @PrePersist
    void alCrear() {
        if (fechaInspeccion == null) {
            fechaInspeccion = OffsetDateTime.now();
        }
    }
}
