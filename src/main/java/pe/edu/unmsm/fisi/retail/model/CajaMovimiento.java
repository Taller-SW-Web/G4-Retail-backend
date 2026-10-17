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
 * Movimiento menor de efectivo dentro de un turno (RF-21). Tabla {@code RET_MOVIMIENTO_CAJA}.
 *
 * <p>Cada fila es un ingreso o un egreso de caja chica. El motivo es obligatorio y la autorizacion
 * del supervisor se guarda como texto ({@code autorizado_por_supervisor}), tal como la digita el
 * cajero segun el contrato: {@code "autorizadoPor": "Supervisor Juan"}.</p>
 *
 * <p>La restriccion de no girar mas efectivo del que hay en gaveta no vive aqui, sino en
 * {@code CajaService}, porque depende de los movimientos anteriores del mismo turno.</p>
 */
@Entity
@Table(name = "RET_MOVIMIENTO_CAJA")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CajaMovimiento {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id_movimiento", nullable = false, updatable = false)
    private UUID idMovimiento;

    /** Turno al que pertenece el movimiento. La tabla lo exige (ON DELETE CASCADE). */
    @Column(name = "caja_sesion_id", nullable = false)
    private UUID cajaSesionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_movimiento", nullable = false, length = 30)
    private TipoMovimientoCaja tipoMovimiento;

    /** La tabla exige que sea estrictamente mayor que cero; el signo lo da el tipo. */
    @Column(name = "monto", nullable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(name = "motivo", nullable = false, columnDefinition = "text")
    private String motivo;

    /** Nombre o codigo de quien autoriza el movimiento. Obligatorio para la auditoria. */
    @Column(name = "autorizado_por_supervisor", nullable = false, length = 100)
    private String autorizadoPorSupervisor;

    @Column(name = "fecha_hora", nullable = false)
    private OffsetDateTime fechaHora;

    @PrePersist
    void alCrear() {
        if (this.fechaHora == null) {
            this.fechaHora = OffsetDateTime.now();
        }
    }
}
