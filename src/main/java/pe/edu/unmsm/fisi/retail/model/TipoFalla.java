package pe.edu.unmsm.fisi.retail.model;

/**
 * Tipo de falla física que el vendedor reporta sobre una prenda (F12, RF-32; contrato §5.12).
 *
 * <p>Retail conserva este detalle; hacia Inventory (G6) viaja resumido en {@code DANIO} o
 * {@code NO_UBICADA} (ver {@code IncidenciaReportadaPayload#tipoInventory}).</p>
 */
public enum TipoFalla {
    MANCHADO_PROBADOR,
    COSTURA_ROTA,
    EXTRAVIO_NO_UBICADO,
    DEFECTO_FABRICA
}
