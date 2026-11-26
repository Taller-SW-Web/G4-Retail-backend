package pe.edu.unmsm.fisi.retail.model;

/**
 * Estado de un evento del outbox (contrato v0.5, §2.9).
 *
 * <ul>
 *   <li>{@link #PENDIENTE}: espera su primer envío o el siguiente reintento.</li>
 *   <li>{@link #ENVIADO}: el módulo destino lo aceptó con un {@code 2xx}.</li>
 *   <li>{@link #FALLIDO}: el destino lo rechazó de forma definitiva o se agotaron los intentos.
 *   Queda para revisión y no se reintenta solo.</li>
 * </ul>
 */
public enum EstadoOutbox {
    PENDIENTE,
    ENVIADO,
    FALLIDO
}
