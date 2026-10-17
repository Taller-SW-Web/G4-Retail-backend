package pe.edu.unmsm.fisi.retail.dto;

/**
 * Cliente identificado en mostrador (F3, RF-07 / RF-08 / RF-09).
 *
 * <p>Es la respuesta de {@code GET /api/v1/retail/clientes} y de
 * {@code POST /api/v1/retail/clientes}. Solo transporta datos de identificacion: el nombre y el
 * apellido viajan ya unidos en {@code nombreCompleto}.</p>
 *
 * @param clienteId       identificador del cliente en el padron
 * @param tipoDocumento   tipo de documento consultado, {@code DNI} o {@code RUC}
 * @param numeroDocumento numero del documento
 * @param nombreCompleto  nombres y apellidos del cliente
 */
public record ClienteResponse(
        String clienteId,
        String tipoDocumento,
        String numeroDocumento,
        String nombreCompleto) {
}
