package pe.edu.unmsm.fisi.retail.dto;

import pe.edu.unmsm.fisi.retail.model.PersonalTienda;

/**
 * Perfil operativo del vendedor autenticado.
 *
 * <p>Es la respuesta de {@code GET /api/v1/retail/perfil}, con la forma que fija la seccion 5.1 del
 * contrato de API v0.4. La SPA la pide justo despues del login para saber quien esta en mostrador y
 * que puede hacer.</p>
 *
 * @param usuarioId      identificador del usuario, el {@code sub} del token
 * @param codigoVendedor codigo visible, por ejemplo {@code VEN-001}
 * @param perfilTienda   {@code VENDEDOR} o {@code SUPERVISOR}
 * @param tiendaId       tienda donde opera
 * @param activo         si el personal esta habilitado para operar
 */
public record PerfilResponse(
        String usuarioId,
        String codigoVendedor,
        String perfilTienda,
        String tiendaId,
        boolean activo) {

    /**
     * Construye la respuesta a partir de la fila de {@code RET_PERSONAL_TIENDA}.
     *
     * <p>TODO (D-05): el contrato define {@code tiendaId} como el {@code store_code} textual, por
     * ejemplo {@code TIENDA-MIRAFLORES}, pero el esquema actual lo guarda como UUID. Aqui se
     * devuelve el UUID en texto para no mentirle al cliente sobre un dato que la base todavia no
     * tiene. Se corrige cuando Angie migre la columna.</p>
     */
    public static PerfilResponse desde(PersonalTienda personal) {
        return new PerfilResponse(
                personal.getUsuarioId().toString(),
                personal.getCodigoVendedor(),
                personal.getPerfilTienda().name(),
                personal.getTiendaId(),
                personal.isActivo());
    }
}
