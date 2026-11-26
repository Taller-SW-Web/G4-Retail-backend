package pe.edu.unmsm.fisi.retail.integracion.inventario;

/**
 * Cuerpo de {@code POST /api/v1/inventario/incidencias} hacia Inventory de Productos (G6).
 *
 * <p>Es la propuesta de Retail del contrato v0.5, §5.12, pendiente de confirmación de G6. El servicio
 * de F12 lo arma al registrar la incidencia y lo deja en el outbox con
 * {@code TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA}.</p>
 *
 * @param incidenciaId       id local de la incidencia; también es la {@code Idempotency-Key}
 * @param canal              siempre {@code RETAIL}
 * @param tiendaId           código de sucursal ({@code TIENDA-...})
 * @param tipoFalla          {@code MANCHADO_PROBADOR}, {@code COSTURA_ROTA}, {@code EXTRAVIO_NO_UBICADO} o
 *                           {@code DEFECTO_FABRICA}
 * @param actaNumero         número del acta de merma (RF-34) si ya la tiene; si no, {@code null}
 * @param fechaReporte       instante del reporte en ISO-8601 UTC
 */
public record IncidenciaReportadaPayload(
        String incidenciaId,
        String canal,
        String tiendaId,
        String sku,
        String codigoBarras,
        String tipoFalla,
        String detalleObservacion,
        String evidenciaFotoUrl,
        String actaNumero,
        String fechaReporte) {

    public static final String CANAL_RETAIL = "RETAIL";
}
