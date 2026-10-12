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

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Personal habilitado para operar el mostrador de una tienda (tabla {@code RET_PERSONAL_TIENDA}).
 *
 * <p>Es la pieza que permite resolver el perfil operativo del usuario autenticado: el token lo
 * emite Seguridad (G7) y <strong>no transporta el perfil</strong>, asi que Retail lo busca aqui
 * usando el {@code sub} del JWT (seccion 2.4 del contrato de API v0.4).</p>
 */
@Entity
@Table(name = "RET_PERSONAL_TIENDA")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PersonalTienda {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id_personal", nullable = false, updatable = false)
    private UUID idPersonal;

    /** Identificador del usuario en Seguridad (G7). Equivale al {@code sub} del JWT. */
    @Column(name = "usuario_id", nullable = false, unique = true)
    private UUID usuarioId;

    /**
     * Tienda a la que pertenece el personal.
     *
     * <p>TODO (D-05): el contrato v0.4 fija {@code tiendaId} como texto, el {@code store_code}
     * canonico (por ejemplo {@code TIENDA-MIRAFLORES}). Aqui se mantiene UUID mientras
     * {@code schema.sql} no migre la columna a {@code text}; coordinar con Angie antes de cambiarlo,
     * porque el cambio afecta a la base en Supabase y al resto de tablas que referencian la tienda.</p>
     */
    @Column(name = "tienda_id", nullable = false)
    private UUID tiendaId;

    /** Codigo visible del vendedor, por ejemplo {@code VEN-001}. */
    @Column(name = "codigo_vendedor", nullable = false, length = 20)
    private String codigoVendedor;

    @Enumerated(EnumType.STRING)
    @Column(name = "perfil_tienda", nullable = false, length = 20)
    private PerfilTienda perfilTienda;

    /** Un perfil inactivo existe pero no puede operar: da {@code 403} en la capa de servicio. */
    @Column(name = "activo", nullable = false)
    private boolean activo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void alCrear() {
        OffsetDateTime ahora = OffsetDateTime.now();
        this.createdAt = ahora;
        this.updatedAt = ahora;
    }

    @PreUpdate
    void alActualizar() {
        this.updatedAt = OffsetDateTime.now();
    }
}
