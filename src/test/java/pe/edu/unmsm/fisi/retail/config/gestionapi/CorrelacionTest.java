package pe.edu.unmsm.fisi.retail.config.gestionapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code X-Correlation-Id} fuera del filtro (contrato v0.5.2, §2.8): solo se reutiliza si es UUID. */
@DisplayName("Correlacion — id de la petición")
class CorrelacionTest {

    private static final String UUID_VALIDO = "6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b";

    @Test
    @DisplayName("Reutiliza un UUID recibido en la cabecera")
    void reutilizaUuid() {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.addHeader(Correlacion.ENCABEZADO, UUID_VALIDO);

        assertThat(Correlacion.de(peticion)).isEqualTo(UUID_VALIDO);
    }

    @Test
    @DisplayName("Genera un UUID si la cabecera no llega o no es UUID")
    void generaSiNoEsUuid() {
        MockHttpServletRequest conTexto = new MockHttpServletRequest();
        conTexto.addHeader(Correlacion.ENCABEZADO, "demo-hito5-001");

        assertThat(Correlacion.de(conTexto)).matches(TrazabilidadFilter.ID_VALIDO);
        assertThat(Correlacion.de(new MockHttpServletRequest())).matches(TrazabilidadFilter.ID_VALIDO);
    }
}
