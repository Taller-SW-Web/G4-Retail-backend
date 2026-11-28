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

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Acta de discrepancia o merma de tienda (tabla {@code RET_ACTA_DISCREPANCIA}, F12, RF-34).
 *
 * <p>Las columnas siguen {@code diseño/schema-s14-f12.sql} de la DBA. El acta es inmutable: no tiene
 * setters, se crea una vez y no se modifica (CA-RF34-03).</p>
 */
@Entity
@Table(name = "RET_ACTA_DISCREPANCIA")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActaDiscrepancia {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id_acta", nullable = false, updatable = false)
    private UUID idActa;

    /** {@code ACTA-MERMA-2026-0012}, correlativo por año y unico en el modulo. */
    @Column(name = "numero_acta", nullable = false, unique = true, length = 30, updatable = false)
    private String numeroActa;

    @Column(name = "tienda_id", nullable = false, columnDefinition = "text", updatable = false)
    private String tiendaId;

    /** SUPERVISOR que emite el acta. */
    @Column(name = "supervisor_id", nullable = false, updatable = false)
    private UUID supervisorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_destino", nullable = false, length = 30, updatable = false)
    private TipoDestinoActa tipoDestino;

    @Column(name = "observaciones", columnDefinition = "text", updatable = false)
    private String observaciones;

    @Column(name = "total_prendas", nullable = false, updatable = false)
    private int totalPrendas;

    @Column(name = "fecha_emision", nullable = false, updatable = false)
    private OffsetDateTime fechaEmision;
}
