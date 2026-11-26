package pe.edu.unmsm.fisi.retail.config.gestionapi;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Propaga el {@code X-Correlation-Id} de la petición en curso a las llamadas que {@code ms-retail} hace
 * a otros módulos (contrato v0.5, §2.8). Se agrega a cada {@code RestClient} de los adaptadores.
 *
 * <p>Si la llamada ya trae la cabecera (por ejemplo, el outbox reenvía la del evento original), no
 * la pisa.</p>
 */
public final class CorrelacionSaliente implements ClientHttpRequestInterceptor {

    public static final CorrelacionSaliente INSTANCIA = new CorrelacionSaliente();

    private CorrelacionSaliente() {
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest peticion, byte[] cuerpo, ClientHttpRequestExecution ejecucion)
            throws IOException {
        String id = Correlacion.actual();
        if (id != null && !peticion.getHeaders().containsKey(Correlacion.ENCABEZADO)) {
            peticion.getHeaders().set(Correlacion.ENCABEZADO, id);
        }
        return ejecucion.execute(peticion, cuerpo);
    }
}
