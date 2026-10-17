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
 * Turno de caja de una terminal: apertura con fondo fijo, movimientos menores y arqueo ciego (F8).
 * Tabla {@code RET_CAJA_SESION}.
 *
 * <p>Es el eje del control de efectivo del mostrador. El fondo fijo se guarda al abrir
 * ({@code saldo_inicial_efectivo}); al cerrar se completan los tres saldos del arqueo: lo que el
 * cajero contó y declaro ({@code saldoFinalDeclarado}), lo que el sistema esperaba
 * ({@code saldoFinalSistema}) y la diferencia entre ambos ({@code diferenciaSaldo}).</p>
 *
 * <p>Los triggers {@code trg_ret_caja_sesion_audit_ins/upd} ya escriben la APERTURA y el CIERRE en
 * {@code RET_AUDITORIA_OPERACIONES}. Por eso esta capa <strong>no</strong> registra auditoria a
 * mano: hacerlo duplicaria el historial.</p>
 */
@Entity
@Table(name = "RET_CAJA_SESION")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CajaSesion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id_sesion", nullable = false, updatable = false)
    private UUID idSesion;

    /**
     * Tienda donde opera la terminal, con su codigo textual ({@code TIENDA-MIRAFLORES}).
     *
     * <p>Decisión D-05, confirmada por el responsable del proyecto: la tienda se identifica con el
     * {@code store_code} textual, no con un UUID. Es un acuerdo entre módulos, así que la columna de
     * esta tabla también es {@code TEXT}. La migración va en {@code MIGRACION-D05-D08.sql}.</p>
     */
    @Column(name = "tienda_id", nullable = false, length = 50)
    private String tiendaId;

    /** Terminal de punto de venta, por ejemplo {@code POS-01}. */
    @Column(name = "terminal_pos_codigo", nullable = false, length = 20)
    private String terminalPosCodigo;

    /** {@code sub} del JWT de quien abre el turno: el identificador del usuario en Seguridad (G7). */
    @Column(name = "vendedor_id", nullable = false)
    private UUID vendedorId;

    @Column(name = "fecha_hora_apertura", nullable = false)
    private OffsetDateTime fechaHoraApertura;

    /** Fondo fijo con el que arranca la gaveta. La tabla exige que nunca sea negativo. */
    @Column(name = "saldo_inicial_efectivo", nullable = false, precision = 12, scale = 2)
    private BigDecimal saldoInicialEfectivo;

    @Column(name = "fecha_hora_cierre")
    private OffsetDateTime fechaHoraCierre;

    /** Conteo fisico que digita el cajero en el arqueo ciego. */
    @Column(name = "saldo_final_declarado", precision = 12, scale = 2)
    private BigDecimal saldoFinalDeclarado;

    /** Lo que el sistema esperaba: fondo fijo + ingresos menores - egresos menores. */
    @Column(name = "saldo_final_sistema", precision = 12, scale = 2)
    private BigDecimal saldoFinalSistema;

    /** {@code declarado - sistema}. Cero cuadra la caja; distinto de cero la deja observada. */
    @Column(name = "diferencia_saldo", precision = 12, scale = 2)
    private BigDecimal diferenciaSaldo;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    private EstadoCajaSesion estado;

    @Column(name = "observaciones_cierre", columnDefinition = "text")
    private String observacionesCierre;

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
            this.estado = EstadoCajaSesion.ABIERTA;
        }
    }

    @PreUpdate
    void alActualizar() {
        this.updatedAt = OffsetDateTime.now();
    }
}
