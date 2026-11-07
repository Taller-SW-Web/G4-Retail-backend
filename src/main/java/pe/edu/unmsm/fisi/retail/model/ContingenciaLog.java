package pe.edu.unmsm.fisi.retail.model;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Reserva durable de RF-28; resultado y cobro se confirman en la misma transacción. */
@Entity @Table(name="RET_CONTINGENCIA_OFFLINE_LOG")
@Getter @Setter @NoArgsConstructor
public class ContingenciaLog {
    @Id @Column(name="id_log") private UUID id;
    @Column(name="tienda_id",nullable=false) private String tiendaId;
    @Column(name="terminal_pos_codigo",nullable=false,length=20) private String terminalPos;
    @Column(name="venta_local_uuid",nullable=false,unique=true,length=64) private String ventaLocalUuid;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="payload_json_orden",nullable=false,columnDefinition="jsonb") private String payload;
    @Column(name="firma_hash_seguridad",nullable=false,length=64) private String hash;
    @Column(name="estado_sincronizacion",nullable=false,length=20) private String estado;
    @Column(name="fecha_emision_offline",nullable=false) private OffsetDateTime fechaOffline;
    @Column(name="fecha_sincronizacion") private OffsetDateTime fechaSincronizacion;
    @Column(name="error_detalle",columnDefinition="text") private String error;
    @Column(name="turno_id") private UUID turnoId;
    @Column(name="vendedor_id") private UUID vendedorId;
    @Column(name="numero_comprobante",unique=true) private Long numeroComprobante;
    @Column(name="pedido_id",length=100) private String pedidoId;
}
