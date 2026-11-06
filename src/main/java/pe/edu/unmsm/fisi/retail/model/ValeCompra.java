package pe.edu.unmsm.fisi.retail.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * Vale de compra emitido en mostrador como compensacion de un cambio (tabla {@code RET_VALE_COMPRA},
 * F9, RF-25).
 *
 * <p>Retail lo emite y lo numera de forma autonoma, con el formato {@code VALE-2026-00001}; Ventas no
 * interviene (reparticion de la Semana 11, confirmado por el PO).</p>
 */
@Entity
@Table(name = "RET_VALE_COMPRA")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValeCompra {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id_vale", nullable = false, updatable = false)
    private UUID idVale;

    @Column(name = "codigo_vale", nullable = false, unique = true, length = 20)
    private String codigoVale;

    @Column(name = "solicitud_id", nullable = false)
    private UUID solicitudId;

    @Column(name = "boleta_numero", nullable = false, length = 20)
    private String boletaNumero;

    @Column(name = "cliente_id")
    private UUID clienteId;

    @Column(name = "tienda_id", nullable = false)
    private String tiendaId;

    @Column(name = "monto", nullable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(name = "fecha_emision", nullable = false)
    private OffsetDateTime fechaEmision;

    @Column(name = "fecha_vencimiento", nullable = false)
    private OffsetDateTime fechaVencimiento;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    private EstadoVale estado;
}
