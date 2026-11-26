package pe.edu.unmsm.fisi.retail.integracion.productos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** {@code POST /promociones/evaluar} de G6 para RF-11 (contrato v0.5, §4.2). */
@DisplayName("PromocionesHttp — motor de promociones de G6")
class PromocionesHttpTest {

    private static final String BASE = "https://g6.example/api/v1";
    private static final List<PromocionesPort.Linea> CESTA =
            List.of(new PromocionesPort.Linea("ZAPVEL-40-AZU", 2, new BigDecimal("249.00")));

    private MockRestServiceServer servidor;
    private PromocionesHttp adaptador;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        servidor = MockRestServiceServer.bindTo(builder).build();
        TokenServicioG7 token = mock(TokenServicioG7.class);
        when(token.obtener()).thenReturn("token-productos");
        adaptador = new PromocionesHttp(builder.build(), token);
    }

    @Test
    @DisplayName("Envía canal RETAIL, líneas sku/quantity y el cupón; lee el descuento de toda la cesta")
    void evaluaConCupon() {
        servidor.expect(requestTo(BASE + "/promociones/evaluar"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-productos"))
                .andExpect(content().json("{\"channel_id\":\"RETAIL\",\"lines\":[{\"sku\":\"ZAPVEL-40-AZU\",\"quantity\":2}],\"coupon_code\":\"VERANO2026\"}"))
                .andRespond(withSuccess("{\"promotion_id\":\"PRM-7\",\"original_amount\":498.0,\"discount_amount\":49.8,"
                        + "\"result_amount\":448.2,\"currency\":\"PEN\"}", MediaType.APPLICATION_JSON));

        PromocionesPort.Evaluacion e = adaptador.evaluar(CESTA, "VERANO2026");

        assertThat(e.aplica()).isTrue();
        assertThat(e.descuentoTotal()).isEqualByComparingTo("49.8");
        assertThat(e.promocionId()).isEqualTo("PRM-7");
        assertThat(e.cuponRechazado()).isFalse();
    }

    @Test
    @DisplayName("Con cupón y sin descuento, el cupón se informa como rechazado")
    void cuponSinBeneficio() {
        servidor.expect(requestTo(BASE + "/promociones/evaluar"))
                .andRespond(withSuccess("{\"promotion_id\":null,\"original_amount\":498.0,\"discount_amount\":0,"
                        + "\"result_amount\":498.0,\"currency\":\"PEN\",\"reason\":\"CUPON_VENCIDO\"}", MediaType.APPLICATION_JSON));

        assertThat(adaptador.evaluar(CESTA, "VENCIDO").cuponRechazado()).isTrue();
    }

    @Test
    @DisplayName("Un 422 de G6 también cuenta como cupón rechazado y no bloquea la venta")
    void contextoInvalido() {
        servidor.expect(requestTo(BASE + "/promociones/evaluar")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        PromocionesPort.Evaluacion e = adaptador.evaluar(CESTA, "NOEXISTE");

        assertThat(e.aplica()).isFalse();
        assertThat(e.cuponRechazado()).isTrue();
    }

    @Test
    @DisplayName("Un 5xx de G6 se informa como PROMOCIONES_NO_DISPONIBLES")
    void motorCaido() {
        servidor.expect(requestTo(BASE + "/promociones/evaluar")).andRespond(withServerError());

        assertThatThrownBy(() -> adaptador.evaluar(CESTA, null))
                .isInstanceOfSatisfying(ServicioExternoNoDisponibleException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo("PROMOCIONES_NO_DISPONIBLES"));
    }
}
