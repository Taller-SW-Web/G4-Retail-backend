package pe.edu.unmsm.fisi.retail.exception;

import lombok.Getter;

/**
 * Un dato de entrada no cumple el formato oficial exigido (por ejemplo, un DNI que no tiene 8
 * digitos). Se traduce a {@code 400} con el codigo {@code DATO_INVALIDO}, el estandar homologado con
 * Ventas (G5) para los documentos de identidad (F3, RF-09).
 *
 * <p>Lleva el campo rechazado para que el cliente lo resalte sin interpretar el texto del mensaje.</p>
 */
@Getter
public class DatoInvalidoException extends RuntimeException {

    /** Codigo estable que consume el cliente (seccion 2.5 del contrato). */
    public static final String CODIGO = "DATO_INVALIDO";

    /** Nombre del campo rechazado, tal como viaja en la peticion. */
    private final String campo;

    public DatoInvalidoException(String campo, String mensaje) {
        super(mensaje);
        this.campo = campo;
    }
}
