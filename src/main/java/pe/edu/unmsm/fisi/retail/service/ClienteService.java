package pe.edu.unmsm.fisi.retail.service;

import pe.edu.unmsm.fisi.retail.dto.ClienteAltaRapidaRequest;
import pe.edu.unmsm.fisi.retail.dto.ClienteResponse;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;

/**
 * Identificacion y alta rapida de clientes en mostrador (F3, RF-07 / RF-08 / RF-09).
 *
 * <p>Limite de la funcionalidad: solo maneja datos de identificacion (documento, nombre, apellido).
 * No procesa ventas, precios, carritos de compra ni cobros de dinero.</p>
 */
public interface ClienteService {

    /**
     * Busca un cliente por su documento en el padron de la tienda.
     *
     * @param tipoDocumento   tipo de documento consultado, {@code DNI} o {@code RUC}
     * @param numeroDocumento numero del documento
     * @return el cliente identificado
     * @throws RecursoNoEncontradoException si el documento no figura en el padron
     */
    ClienteResponse buscarPorDocumento(String tipoDocumento, String numeroDocumento);

    /**
     * Registra un cliente nuevo capturado en mostrador (RF-08).
     *
     * @param request datos de identificacion del cliente
     * @return el cliente registrado
     * @throws ConflictoException si el documento ya esta registrado
     */
    ClienteResponse registrarAltaRapida(ClienteAltaRapidaRequest request);
}
