package pe.edu.unmsm.fisi.retail.service;

import pe.edu.unmsm.fisi.retail.exception.DatoInvalidoException;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validacion estricta de documentos de identidad en mostrador (F3, RF-09).
 *
 * <p>Reglas homologadas con Ventas (G5) y Seguridad (G7):</p>
 * <ul>
 *   <li><strong>DNI:</strong> exactamente 8 digitos numericos ({@code ^\d{8}$}).</li>
 *   <li><strong>RUC:</strong> exactamente 11 digitos que empiezan con 10 o 20 ({@code ^(10|20)\d{9}$}).</li>
 *   <li><strong>CE</strong> (carne de extranjeria): 9 a 12 caracteres alfanumericos.</li>
 *   <li><strong>PASAPORTE:</strong> 6 a 16 caracteres alfanumericos.</li>
 * </ul>
 *
 * <p>En Java {@code \d} solo reconoce los digitos ASCII 0-9, asi que digitos de otros alfabetos,
 * espacios, guiones o letras con tilde se rechazan. No se recorta ni se limpia el valor: un documento
 * con espacios es un dato mal capturado y se devuelve al vendedor para corregirlo.</p>
 */
public final class ValidadorDocumento {

    /** Tipos de documento que atiende el mostrador, cada uno con su expresion regular oficial. */
    public enum TipoDocumento {
        DNI("^\\d{8}$", "El DNI debe tener exactamente 8 digitos numericos"),
        RUC("^(10|20)\\d{9}$", "El RUC debe tener exactamente 11 digitos numericos y empezar con 10 o 20"),
        CE("^[A-Za-z0-9]{9,12}$", "El carne de extranjeria debe tener de 9 a 12 caracteres alfanumericos"),
        PASAPORTE("^[A-Za-z0-9]{6,16}$", "El pasaporte debe tener de 6 a 16 caracteres alfanumericos");

        private final Pattern patron;
        private final String mensaje;

        TipoDocumento(String regex, String mensaje) {
            this.patron = Pattern.compile(regex);
            this.mensaje = mensaje;
        }

        /** Indica si el numero cumple el formato oficial de este tipo de documento. */
        public boolean admite(String numeroDocumento) {
            return numeroDocumento != null && patron.matcher(numeroDocumento).matches();
        }
    }

    /**
     * Documento que ya paso la validacion, normalizado para usarlo como clave del padron. CE y
     * pasaporte se guardan en mayusculas para que {@code ab123456} y {@code AB123456} sean el mismo.
     */
    public record DocumentoValidado(TipoDocumento tipo, String numero) {
    }

    private static final String TIPOS_ADMITIDOS = Arrays.stream(TipoDocumento.values())
            .map(Enum::name)
            .collect(Collectors.joining(", "));

    private ValidadorDocumento() {
    }

    /**
     * Valida el tipo y el numero de documento.
     *
     * @param tipoDocumento   {@code DNI}, {@code RUC}, {@code CE} o {@code PASAPORTE} (sin distinguir mayusculas)
     * @param numeroDocumento numero tal como lo capturo el vendedor
     * @return el documento normalizado
     * @throws DatoInvalidoException si el tipo no se reconoce o el numero no cumple el formato
     */
    public static DocumentoValidado validar(String tipoDocumento, String numeroDocumento) {
        TipoDocumento tipo = resolverTipo(tipoDocumento);
        if (numeroDocumento == null || numeroDocumento.isEmpty()) {
            throw new DatoInvalidoException("numeroDocumento", "El campo numeroDocumento es obligatorio");
        }
        if (!tipo.admite(numeroDocumento)) {
            throw new DatoInvalidoException("numeroDocumento", tipo.mensaje);
        }
        String numero = (tipo == TipoDocumento.CE || tipo == TipoDocumento.PASAPORTE)
                ? numeroDocumento.toUpperCase(Locale.ROOT)
                : numeroDocumento;
        return new DocumentoValidado(tipo, numero);
    }

    private static TipoDocumento resolverTipo(String tipoDocumento) {
        if (tipoDocumento == null || tipoDocumento.isBlank()) {
            throw new DatoInvalidoException("tipoDocumento", "El campo tipoDocumento es obligatorio");
        }
        try {
            return TipoDocumento.valueOf(tipoDocumento.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new DatoInvalidoException("tipoDocumento",
                    "Tipo de documento no admitido: " + tipoDocumento + ". Use " + TIPOS_ADMITIDOS);
        }
    }
}
