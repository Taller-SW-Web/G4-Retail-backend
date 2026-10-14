package pe.edu.unmsm.fisi.retail.model;

/**
 * Perfil operativo del personal dentro de la tienda.
 *
 * <p>La decision D-08 del contrato de API v0.4 establece un unico perfil de mostrador,
 * {@link #VENDEDOR}: la misma persona atiende, vende, cobra y opera la caja. {@link #CAJERO}
 * queda como valor historico y solo se conserva porque el CHECK de {@code RET_PERSONAL_TIENDA}
 * en schema.sql todavia lo admite; no debe asignarse a personal nuevo.</p>
 */
public enum PerfilTienda {

    /** Perfil operativo de mostrador. Unico perfil vigente segun D-08. */
    VENDEDOR,

    /** Obsoleto por D-08. Se mantiene solo para no romper el mapeo de filas existentes. */
    CAJERO,

    /** Vendedor con atribuciones adicionales: egresos de caja, actas de merma y excepciones. */
    SUPERVISOR
}
