package pe.edu.unmsm.fisi.retail.integracion.productos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.CampanaComercial;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** {@code GET /promociones?canal=RETAIL} de G6 para RF-31 (contrato v0.5, §5.11). */
@DisplayName("CampanasHttp — promociones vigentes del canal RETAIL en G6")
class CampanasHttpTest {

    @Test
    @DisplayName("Pide las promociones vigentes del canal RETAIL y descarta las INACTIVA")
    void listaActivasRetail() {
        String base = "https://g6.example/api/v1";
        RestClient.Builder builder = RestClient.builder().baseUrl(base);
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        TokenServicioG7 token = mock(TokenServicioG7.class);
        when(token.obtener()).thenReturn("token-productos");
        servidor.expect(requestTo(base + "/promociones?canal=RETAIL&vigentes=true"))
                .andExpect(header("Authorization", "Bearer token-productos"))
                .andRespond(withSuccess("""
                        {"items":[
                          {"promotion_id":"PRM-1","name":"30% chimpunes","status":"ACTIVA","valid_from":"2026-11-20T05:00:00Z",
                           "valid_until":"2026-11-30T04:59:59Z","channels":["RETAIL","MARKETPLACE"]},
                          {"promotion_id":"PRM-2","name":"Pausada","status":"INACTIVA","valid_from":"2026-11-20T05:00:00Z",
                           "valid_until":null,"channels":["RETAIL"]}]}""", MediaType.APPLICATION_JSON));

        List<CampanaComercial> lista = new CampanasHttp(builder.build(), token).listarCampanas();

        assertThat(lista).extracting(CampanaComercial::campanaId).containsExactly("PRM-1");
        assertThat(lista.get(0).vigentePara("RETAIL", OffsetDateTime.parse("2026-11-28T15:00:00Z"))).isTrue();
        servidor.verify();
    }
}
