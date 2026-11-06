package pe.edu.unmsm.fisi.retail.dto;

/**
 * Formato del numero de comprobante: serie de una letra ({@code B} boleta, {@code F} factura) y tres
 * digitos, guion y correlativo de 8 digitos. Por ejemplo {@code B001-00000045}.
 *
 * <p>Es el formato del contrato de API, confirmado por el PO para la Semana 11.</p>
 */
public final class BoletaNumero {

    public static final String PATRON = "^[BF]\\d{3}-\\d{8}$";
    public static final String MENSAJE = "El comprobante debe tener el formato B001-00000045";

    private BoletaNumero() {
    }
}
