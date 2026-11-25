package pe.edu.unmsm.fisi.retail.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Numero oficial del acta de merma de tienda (F12, RF-34): {@code ACTA-MERMA-2026-0012}.
 *
 * <p>El formato sigue el contrato (seccion 5.12): año de emision en la zona horaria de la tienda y un
 * correlativo de al menos cuatro digitos. El correlativo sale de una secuencia de la base, que lo hace
 * unico aunque dos supervisores emitan a la vez; esa secuencia la define la DBA junto con la tabla
 * {@code RET_ACTA_DISCREPANCIA}.</p>
 *
 * <p>Pasado el {@code 9999} el numero no se corta: sigue con cinco digitos. Truncarlo repetiria
 * numeros, y el acta debe ser inmutable (CA-RF34-03).</p>
 */
public class GeneradorNumeroActa {

    private final Clock reloj;
    private final LongSupplier secuencia;

    public GeneradorNumeroActa(Clock reloj, LongSupplier secuencia) {
        this.reloj = reloj;
        this.secuencia = secuencia;
    }

    /** Toma el siguiente correlativo y arma el numero del acta. */
    public String siguiente() {
        long correlativo = secuencia.getAsLong();
        if (correlativo < 1) {
            throw new IllegalStateException("La secuencia de actas devolvio un correlativo no valido: " + correlativo);
        }
        int anio = Instant.now(reloj).atZone(PoliticaPlazoCambio.ZONA_TIENDA).getYear();
        return String.format(Locale.ROOT, "ACTA-MERMA-%d-%04d", anio, correlativo);
    }
}
