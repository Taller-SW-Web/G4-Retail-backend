package pe.edu.unmsm.fisi.retail.service.outbox;

import pe.edu.unmsm.fisi.retail.model.OutboxEvento;

/**
 * Módulo que recibe los eventos del outbox. Cada adaptador (real o simulado) implementa uno.
 *
 * <p>El despachador elige el destino por {@link #nombre()}, que coincide con
 * {@link OutboxEvento#getDestino()}.</p>
 */
public interface DestinoOutbox {

    /** Inventory de Productos (G6). */
    String PRODUCTOS_G6 = "PRODUCTOS_G6";

    /** Nombre del destino que atiende este adaptador, por ejemplo {@link #PRODUCTOS_G6}. */
    String nombre();

    /**
     * Envía el evento al módulo destino con {@code Idempotency-Key = agregadoId}.
     *
     * @return el id que devolvió el destino al aceptarlo (puede ser {@code null})
     * @throws EnvioOutboxException si el destino no lo aceptó; indica si conviene reintentar
     */
    String enviar(OutboxEvento evento);

    /** Fuente de datos, para los logs: {@code PRODUCTOS_G6} o {@code EN_MEMORIA}. */
    String origen();
}
