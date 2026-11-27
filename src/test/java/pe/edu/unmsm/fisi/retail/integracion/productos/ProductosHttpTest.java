package pe.edu.unmsm.fisi.retail.integracion.productos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.DisponibilidadComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.FiltroCatalogo;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.PaginaProductos;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.Atributo;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.VarianteComercial;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

/** Adaptador real hacia Productos (G6) con las rutas de su OpenAPI (contrato v0.5, §4.2). */
@DisplayName("ProductosHttp — catálogo, precios y disponibilidad contra G6")
class ProductosHttpTest {

    private static final String BASE = "https://g6.example/api/v1";

    private MockRestServiceServer servidor;
    private ProductosHttp adaptador;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        servidor = MockRestServiceServer.bindTo(builder).build();
        TokenServicioG7 token = mock(TokenServicioG7.class);
        when(token.obtener()).thenReturn("token-productos");
        adaptador = new ProductosHttp(builder.build(), token, Clock.systemUTC(), Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("Lista el catálogo del canal RETAIL con la paginación de G6 (desde 1) y el token de servicio")
    void listaCatalogo() {
        servidor.expect(requestTo(startsWith(BASE + "/productos?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("canal", "RETAIL"))
                .andExpect(queryParam("pagina", "1"))
                .andExpect(queryParam("tamanio", "20"))
                .andExpect(queryParam("q", "zapatilla"))
                .andExpect(header("Authorization", "Bearer token-productos"))
                .andRespond(withSuccess("""
                        {"items":[{"product_id":"PROD-101","slug":"z","name":"Zapatillas Velocity X","categoria_id":"C",
                        "tipo_producto_id":"T","marca_id":"M","tiene_variantes":true,"images":[],"attributes":[]}],
                        "pagina":1,"tamanio":20,"total":1}""", MediaType.APPLICATION_JSON));

        PaginaProductos pagina = adaptador.listarProductos(FiltroCatalogo.desdePaginacionRetail("zapatilla", null, null, 0, 20));

        assertThat(pagina.items()).extracting("name").containsExactly("Zapatillas Velocity X");
        assertThat(pagina.total()).isEqualTo(1);
    }

    @Test
    @DisplayName("Lee las variantes del detalle comercial GET /productos/{id}?canal=RETAIL (M2M) y las guarda en caché")
    void variantesEnCache() {
        servidor.expect(ExpectedCount.once(), requestTo(BASE + "/productos/PROD-101?canal=RETAIL"))
                .andRespond(withSuccess("""
                        {"product_id":"PROD-101","slug":"z","name":"Zapatillas Velocity X","tiene_variantes":true,
                         "marca":{"marca_id":"M","name":"Velocity"},
                         "variants":[{"variant_id":"V1","sku":"ZAPVEL-40-AZU",
                         "identifying_attributes":[{"caracteristica_id":"CAR-TALLA","nombre":"Talla","valor":"40"}]}]}
                        """, MediaType.APPLICATION_JSON));

        List<VarianteComercial> primera = adaptador.listarVariantes("PROD-101");
        List<VarianteComercial> segunda = adaptador.listarVariantes("PROD-101");

        assertThat(primera).extracting(VarianteComercial::sku).containsExactly("ZAPVEL-40-AZU");
        assertThat(primera.get(0).atributo("Talla")).contains("40");
        assertThat(segunda).isEqualTo(primera);
        servidor.verify();
    }

    @Test
    @DisplayName("Acepta atributos con valor de cualquier tipo, como permite el esquema Atributo de G6")
    void atributosConValorDeCualquierTipo() {
        servidor.expect(requestTo(BASE + "/productos/PROD-102?canal=RETAIL"))
                .andRespond(withSuccess("""
                        {"product_id":"PROD-102","attributes":[{"caracteristica_id":"A","nombre":"Material","valor":{"es":"Malla"}}],
                         "variants":[{"variant_id":"V1","sku":"POL-M-ROJ","images":[],
                          "identifying_attributes":[{"caracteristica_id":"CAR-TALLA","nombre":"Talla","valor_id":"T-M","valor":"M"},
                                                    {"caracteristica_id":"CAR-NUM","nombre":"Numero","valor":42}],
                          "non_identifying_attributes":[{"caracteristica_id":"CAR-TEC","nombre":"Tecnologias","valor":["Dry","UV"]},
                                                        {"caracteristica_id":"CAR-IMP","nombre":"Impermeable","valor":true},
                                                        {"caracteristica_id":"CAR-MED","nombre":"Medidas","valor":{"largo":70}},
                                                        {"caracteristica_id":"CAR-NUL","nombre":"Nota","valor":null}]}]}
                        """, MediaType.APPLICATION_JSON));

        VarianteComercial variante = adaptador.listarVariantes("PROD-102").get(0);

        assertThat(variante.atributo("Talla")).contains("M");
        assertThat(variante.atributo("Numero")).contains("42");
        assertThat(variante.nonIdentifyingAttributes()).extracting(Atributo::valor)
                .containsExactly("Dry, UV", "true", "{\"largo\":70}", null);
    }

    @Test
    @DisplayName("Talla y color reales de G6 llegan como {nombre, valor_id}: se toma el nombre")
    void tallaYColorComoObjeto() {
        servidor.expect(requestTo(BASE + "/productos/PROD-103?canal=RETAIL"))
                .andRespond(withSuccess("""
                        {"product_id":"PROD-103","variants":[{"variant_id":"V1","sku":"ADI-HT5027-NEG-S",
                         "identifying_attributes":[
                           {"caracteristica_id":"CAR-TALLA","nombre":"Talla","valor":{"nombre":"S","valor_id":"6787802a"}},
                           {"caracteristica_id":"CAR-COLOR","nombre":"Color","valor":{"nombre":"Negro","valor_id":"61174517"}}]}]}
                        """, MediaType.APPLICATION_JSON));

        VarianteComercial variante = adaptador.listarVariantes("PROD-103").get(0);

        assertThat(variante.atributo("Talla")).contains("S");
        assertThat(variante.atributo("Color")).contains("Negro");
    }

    @Test
    @DisplayName("Lee los nombres de marcas y categorías de G6 y los guarda en caché")
    void nombresDeMarcasYCategorias() {
        servidor.expect(ExpectedCount.once(), requestTo(BASE + "/marcas"))
                .andRespond(withSuccess("""
                        {"brands":[{"marca_id":"M1","name":"adidas","status":"ACTIVA"},{"marca_id":"M2"}]}
                        """, MediaType.APPLICATION_JSON));
        servidor.expect(ExpectedCount.once(), requestTo(BASE + "/categorias"))
                .andRespond(withSuccess("""
                        {"categories":[{"categoria_id":"C1","parent_id":null,"name":"Medias","status":"ACTIVA"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adaptador.nombresDeMarcas()).containsExactlyEntriesOf(java.util.Map.of("M1", "adidas"));
        assertThat(adaptador.nombresDeMarcas()).containsEntry("M1", "adidas");
        assertThat(adaptador.nombresDeCategorias()).containsExactlyEntriesOf(java.util.Map.of("C1", "Medias"));
        servidor.verify();
    }

    @Test
    @DisplayName("Si G6 no entrega las marcas, el catálogo sigue con los ids")
    void sinNombresSiG6Falla() {
        servidor.expect(requestTo(BASE + "/marcas")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(adaptador.nombresDeMarcas()).isEmpty();
    }

    @Test
    @DisplayName("Un producto que no está activo en el canal (404) no tiene variantes vendibles")
    void variantesDeProductoNoVisible() {
        servidor.expect(requestTo(BASE + "/productos/PROD-404?canal=RETAIL"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(adaptador.listarVariantes("PROD-404")).isEmpty();
    }

    @Test
    @DisplayName("Resuelve el código de barras enviando codigo_barras; un 404 significa que no existe")
    void codigoBarras() {
        servidor.expect(requestTo(BASE + "/productos/codigos-barras/resolver"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"codigo_barras\":\"7750101000012\"}"))
                .andRespond(withSuccess("{\"sku\":\"ZAPVEL-38-NEG\"}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/productos/codigos-barras/resolver"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(adaptador.resolverCodigoBarras("7750101000012")).contains("ZAPVEL-38-NEG");
        assertThat(adaptador.resolverCodigoBarras("0000000000000")).isEmpty();
    }

    @Test
    @DisplayName("Consulta el precio del canal RETAIL; un 404 es precio inexistente")
    void precio() {
        servidor.expect(requestTo(BASE + "/precios/skus/ZAPVEL-38-NEG?canal=RETAIL"))
                .andRespond(withSuccess("{\"sku\":\"ZAPVEL-38-NEG\",\"precio_regular\":249.0,\"precio_oferta\":199.0,"
                        + "\"currency\":\"PEN\",\"price_version\":3}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/precios/skus/SIN-PRECIO?canal=RETAIL"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(adaptador.consultarPrecio("ZAPVEL-38-NEG")).hasValueSatisfying(p ->
                assertThat(p.precioEfectivo()).isEqualByComparingTo("199.0"));
        assertThat(adaptador.consultarPrecio("SIN-PRECIO")).isEmpty();
    }

    @Test
    @DisplayName("Parte la consulta de disponibilidad en tramos de 100 SKU (límite de G6)")
    void disponibilidadEnTramos() {
        List<String> skus = IntStream.range(0, 150).mapToObj(i -> "SKU-" + i).toList();
        servidor.expect(ExpectedCount.twice(), requestTo(startsWith(BASE + "/inventario/disponibilidad/comercial?")))
                .andExpect(queryParam("canal", "RETAIL"))
                .andRespond(withSuccess("{\"items\":[{\"sku\":\"SKU-1\",\"status\":\"STOCK_BAJO\"}]}", MediaType.APPLICATION_JSON));

        List<DisponibilidadComercial> lista = adaptador.consultarDisponibilidad(skus);

        assertThat(lista).hasSize(2);
        assertThat(lista.get(0).status()).isEqualTo(DisponibilidadComercial.Estado.STOCK_BAJO);
        servidor.verify();
    }

    @Test
    @DisplayName("Un 503 de G6 se responde como 503 PRODUCTOS_NO_DISPONIBLE; un 403 como credencial rechazada")
    void errores() {
        servidor.expect(requestTo(startsWith(BASE + "/productos?"))).andRespond(withServerError());
        servidor.expect(requestTo(startsWith(BASE + "/productos?"))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adaptador.listarProductos(FiltroCatalogo.primeraPagina(20)))
                .isInstanceOfSatisfying(ServicioExternoNoDisponibleException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo("PRODUCTOS_NO_DISPONIBLE"));
        assertThatThrownBy(() -> adaptador.listarProductos(FiltroCatalogo.primeraPagina(20)))
                .isInstanceOfSatisfying(ServicioExternoNoDisponibleException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo("PRODUCTOS_CREDENCIAL_RECHAZADA"));
    }
}
