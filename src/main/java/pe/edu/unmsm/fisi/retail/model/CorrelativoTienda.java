package pe.edu.unmsm.fisi.retail.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * Ultimo numero usado de un correlativo por tienda y año (tabla {@code RET_CORRELATIVO}).
 *
 * <p>La constancia de cambio ({@code CAMB-2026-000123}) se numera por año y tienda; una secuencia de
 * la base no sirve porque tendria que reiniciarse por cada tienda y cada año.</p>
 */
@Entity
@Table(name = "RET_CORRELATIVO")
@IdClass(CorrelativoTienda.Clave.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CorrelativoTienda {

    @Id
    @Column(name = "tienda_id", nullable = false, columnDefinition = "text")
    private String tiendaId;

    @Id
    @Column(name = "anio", nullable = false)
    private int anio;

    @Id
    @Column(name = "tipo", nullable = false, length = 20)
    private String tipo;

    @Column(name = "ultimo", nullable = false)
    private int ultimo;

    /** Clave compuesta: tienda, año y tipo de correlativo. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Clave implements Serializable {
        private String tiendaId;
        private int anio;
        private String tipo;
    }
}
