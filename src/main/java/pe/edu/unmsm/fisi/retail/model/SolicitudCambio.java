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

    /** Tienda del vendedor que atendio el cambio, para consultar por tienda. */
    @Column(name = "tienda_id", columnDefinition = "text")
    private String tiendaId;

    @Column(name = "venta_id")
    private UUID ventaId;

    /** Vendedor que hizo la inspeccion (RF-24: el registro guarda al auditor). */
    @Column(name = "vendedor_id", nullable = false)
    private UUID vendedorId;

    /** Comprobante con el que se compro la prenda, por ejemplo {@code B001-00000045}. */
    @Column(name = "boleta_numero", nullable = false, length = 20)
    private String boletaNumero;

    /** Pedido central en Ventas (G5), si se conoce. */
    @Column(name = "pedido_id_origen", columnDefinition = "text")
    private String pedidoIdOrigen;

    /** SKU de la prenda devuelta. */
    @Column(name = "variante_sku_devuelta_id", nullable = false, columnDefinition = "text")
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

    /** Codigo del vale de la Semana 11. El cambio directo no emite vales: queda nulo. */
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

    // ---- Cambio directo (Semana 14, contrato 5.9). Nulos en las filas del vale de la Semana 11. ----

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_cambio", length = 20)
    private TipoCambio tipoCambio;

    /** CAMB-2026-000123, correlativo por año y tienda. */
    @Column(name = "numero_constancia", length = 20)
    private String numeroConstancia;

    /** Lo que el cliente pago por las unidades devueltas, segun RET_VENTA. */
    @Column(name = "monto_credito", precision = 12, scale = 2)
    private BigDecimal montoCredito;

    /** Precio vigente de las prendas nuevas, sin cupon. */
    @Column(name = "total_nuevo", precision = 12, scale = 2)
    private BigDecimal totalNuevo;

    /** Lo que se cobro: 0 en mismo valor. */
    @Column(name = "diferencia", precision = 12, scale = 2)
    private BigDecimal diferencia;

    /** Pedido creado en Ventas por el cambio; nulo mientras esta PENDIENTE. */
    @Column(name = "pedido_id_nuevo", columnDefinition = "text")
    private String pedidoIdNuevo;

    /** Boleta de las prendas nuevas, solo en mayor valor. */
    @Column(name = "venta_nueva_id")
    private UUID ventaNuevaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_inventario", length = 20)
    private EstadoInventarioCambio estadoInventario;

    /** Pedido para Ventas en JSON, que el job reenvia mientras el cambio esta PENDIENTE. */
    @Column(name = "pedido_pendiente", columnDefinition = "text")
    private String pedidoPendiente;

    @Column(name = "intentos_envio", nullable = false)
    private int intentosEnvio;

    /** Huella de la peticion: la misma clave con otra peticion es un conflicto, no un reintento. */
    @Column(name = "huella", length = 64)
    private String huella;

    /** Respuesta 201 en JSON: un reintento con la misma clave devuelve exactamente esto. */
    @Column(name = "respuesta", columnDefinition = "text")
    private String respuesta;

    @PrePersist
    void alCrear() {
        if (fechaInspeccion == null) {
            fechaInspeccion = OffsetDateTime.now();
        }
    }
}
