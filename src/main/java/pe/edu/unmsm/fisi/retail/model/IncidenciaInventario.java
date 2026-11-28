package pe.edu.unmsm.fisi.retail.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Incidencia de inventario reportada en el mostrador: prenda dañada o no ubicada (tabla
 * {@code RET_INCIDENCIA_INVENTARIO}, F12: RF-32, RF-33 y RF-34).
 *
 * <p>Es una <strong>proyección local</strong> de la incidencia que Retail reporta a Inventory (G6):
 * sirve para la bandeja de cuarentena y para bloquear la venta del SKU en el POS. El saldo central lo
 * lleva Inventory (G4-P0-05), así que ninguna columna mueve stock.</p>
 *
 * <p>El mapeo sigue {@code diseño/schema-s14-f12.sql} de G4-Retail-specs. En la nube el backend arranca
 * con {@code ddl-auto=validate}: si el DDL cambia un nombre o un tipo, esta clase debe cambiar igual.
 * Las columnas {@code intentos_reporte} y {@code ultimo_error_reporte} del DDL no se mapean: los
 * reintentos hacia G6 los lleva {@code RET_OUTBOX_EVENTO} (contrato §2.9).</p>
 *
 * <p>El acta de merma (RF-34) liquida la incidencia con {@link #actaId} y la pasa a
 * {@link EstadoIncidencia#DERIVADO_ALMACEN} o {@link EstadoIncidencia#BAJA_DEFINITIVA}.</p>
 */
@Entity
@Table(name = "RET_INCIDENCIA_INVENTARIO")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncidenciaInventario {

    /** {@code incidenciaId}. Lo asigna el servicio antes de guardar: viaja en el outbox como Idempotency-Key. */
    @Id
    @Column(name = "id_incidencia", nullable = false, updatable = false)
    private UUID idIncidencia;

    /** Código de sucursal ({@code store_code}, por ejemplo {@code TIENDA-MIRAFLORES}). */
    @Column(name = "tienda_id", nullable = false, columnDefinition = "text")
    private String tiendaId;

    /** Vendedor que reportó la prenda (CA-RF32-03). Referencia lógica a Seguridad. */
    @Column(name = "vendedor_reporta_id", nullable = false)
    private UUID vendedorReportaId;

    /** SKU opaco de Productos (G6). */
    @Column(name = "variante_sku_id", nullable = false, columnDefinition = "text")
    private String sku;

    /** Nombre de la prenda al reportar, para la bandeja. Nulo si G6 no respondió. */
    @Column(name = "nombre_producto", length = 150)
    private String nombreProducto;

    /** Código leído por el escáner; si se buscó por SKU, el propio SKU. */
    @Column(name = "codigo_barras", nullable = false, length = 50)
    private String codigoBarras;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_falla", nullable = false, length = 50)
    private TipoFalla tipoFalla;

    @Column(name = "detalle_observacion", nullable = false, columnDefinition = "text")
    private String detalleObservacion;

    @Column(name = "evidencia_foto_url", length = 255)
    private String evidenciaFotoUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_cuarentena", nullable = false, length = 30)
    private EstadoIncidencia estado;

    /** {@code Idempotency-Key} del {@code POST /incidencias}: un reintento no duplica el reporte. */
    @Column(name = "idempotency_key", length = 64, unique = true)
    private String idempotencyKey;

    /** {@code incidencia_id} que devuelve Inventory al aceptar el reporte. Nulo en {@code PENDIENTE_REPORTE}. */
    @Column(name = "incidencia_inventory_id", columnDefinition = "text")
    private String incidenciaInventoryId;

    /** Acta de merma que liquida la incidencia (RF-34). FK a {@code RET_ACTA_DISCREPANCIA}. */
    @Column(name = "acta_id")
    private UUID actaId;

    @Column(name = "fecha_reporte", nullable = false, updatable = false)
    private OffsetDateTime fechaReporte;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void alCrear() {
        if (idIncidencia == null) {
            idIncidencia = UUID.randomUUID();
        }
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        if (fechaReporte == null) {
            fechaReporte = ahora;
        }
        if (estado == null) {
            estado = EstadoIncidencia.PENDIENTE_REPORTE;
        }
        updatedAt = ahora;
    }

    @PreUpdate
    void alActualizar() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Si la incidencia sigue bloqueando la venta del SKU (RF-33). */
    public boolean enCuarentenaActiva() {
        return estado != null && estado.bloqueaVenta();
    }
}
