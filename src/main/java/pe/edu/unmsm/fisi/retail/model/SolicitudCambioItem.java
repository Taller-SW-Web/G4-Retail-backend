package pe.edu.unmsm.fisi.retail.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/** Prenda nueva que el cliente se lleva en un cambio directo (tabla {@code RET_SOLICITUD_CAMBIO_ITEM}). */
@Entity
@Table(name = "RET_SOLICITUD_CAMBIO_ITEM")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SolicitudCambioItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id_item", nullable = false, updatable = false)
    private UUID idItem;

    @Column(name = "solicitud_id", nullable = false)
    private UUID solicitudId;

    @Column(name = "sku", nullable = false, columnDefinition = "text")
    private String sku;

    @Column(name = "cantidad", nullable = false)
    private int cantidad;

    /** Precio vigente por unidad al momento del cambio, sin cupon. */
    @Column(name = "precio_unitario", nullable = false, precision = 12, scale = 2)
    private BigDecimal precioUnitario;
}
