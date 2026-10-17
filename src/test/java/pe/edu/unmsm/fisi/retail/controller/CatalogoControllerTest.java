package pe.edu.unmsm.fisi.retail.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de {@code /api/v1/retail/catalogo} (F2: RF-04, RF-05 y RF-06).
 *
 * <p>Verifica los criterios de aceptacion que importan en mostrador: que el catalogo no se entregue
 * sin token, que la busqueda por texto y por categoria filtre, que una busqueda sin coincidencias
 * devuelva una pagina vacia en lugar de romperse, que el escaneo resuelva la variante exacta con su
 * talla y color, y que el semaforo de disponibilidad hable el vocabulario de Retail y no el de G6.</p>
 *
 * <p>Los datos salen del mock de Productos ({@code mocks/catalogo-g6.json}), activo con el perfil
 * {@code mock}: 18 articulos deportivos con 40 SKUs y su codigo de barras EAN-13.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mock"})
class CatalogoControllerTest {

    private static final String VENDEDOR = "bbbbbbbb-0000-0000-0000-000000000001";

    /** Zapatillas Velocity X, talla 38 negra: existe, esta disponible y cuesta S/ 249.00. */
    private static final String CODIGO_DISPONIBLE = "7750101000012";

    /** Zapatillas Trail Andes, talla 42 gris: el mock la informa agotada. */
    private static final String CODIGO_AGOTADO = "7750102000073";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("Sin token no se entrega el catalogo")
    void sinTokenResponde401() throws Exception {
        mvc.perform(get("/api/v1/retail/catalogo"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("TOKEN_INVALIDO"));
    }

    @Test
    @DisplayName("Sin filtros devuelve la primera pagina del catalogo, numerada desde 0")
    void devuelveLaPrimeraPagina() throws Exception {
        mvc.perform(get("/api/v1/retail/catalogo").with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(18))
                .andExpect(jsonPath("$.items[0].productoId").value("PROD-101"))
                .andExpect(jsonPath("$.items[0].nombre").value("Zapatillas Velocity X"));
    }

    @Test
    @DisplayName("La busqueda por texto ignora tildes y mayusculas (RF-04)")
    void buscaPorTextoSinImportarTildes() throws Exception {
        mvc.perform(get("/api/v1/retail/catalogo").param("q", "ZAPATILLAS")
                        .with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].productoId").value("PROD-101"));
    }

    @Test
    @DisplayName("El filtro por categoria acota la grilla a esa disciplina (RF-04)")
    void filtraPorCategoria() throws Exception {
        mvc.perform(get("/api/v1/retail/catalogo").param("categoria", "CAT-RUNNING")
                        .with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].categoria").value("CAT-RUNNING"));
    }

    @Test
    @DisplayName("Una busqueda sin coincidencias devuelve pagina vacia, no un error")
    void busquedaSinCoincidenciasDevuelvePaginaVacia() throws Exception {
        mvc.perform(get("/api/v1/retail/catalogo").param("q", "patines de hielo")
                        .with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("Cada producto trae su matriz de variantes con talla, color y precio (RF-05)")
    void cadaProductoTraeSusVariantes() throws Exception {
        mvc.perform(get("/api/v1/retail/catalogo").param("q", "Velocity")
                        .with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].precio").value(249.0))
                .andExpect(jsonPath("$.items[0].variantes[0].sku").value("ZAPVEL-38-NEG"))
                .andExpect(jsonPath("$.items[0].variantes[0].talla").value("38"))
                .andExpect(jsonPath("$.items[0].variantes[0].color").value("Negro"))
                .andExpect(jsonPath("$.items[0].variantes[0].precio").value(249.0))
                .andExpect(jsonPath("$.items[0].variantes[0].disponibilidad").value("DISPONIBLE"));
    }

    @Test
    @DisplayName("El semaforo usa el vocabulario de Retail, no el de G6 (RF-06)")
    void traduceElEstadoDeDisponibilidad() throws Exception {
        mvc.perform(get("/api/v1/retail/catalogo").param("q", "Velocity")
                        .with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isOk())
                // El mock informa STOCK_BAJO para la talla 40 azul; el contrato lo llama asi:
                .andExpect(jsonPath("$.items[0].variantes[2].sku").value("ZAPVEL-40-AZU"))
                .andExpect(jsonPath("$.items[0].variantes[2].disponibilidad").value("ULTIMAS_UNIDADES"));
    }

    @Test
    @DisplayName("Escanear una etiqueta devuelve la variante exacta, no el producto entero (RF-04)")
    void escanearDevuelveLaVarianteExacta() throws Exception {
        mvc.perform(get("/api/v1/retail/catalogo/codigo-barras/{codigo}", CODIGO_DISPONIBLE)
                        .with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigoBarras").value(CODIGO_DISPONIBLE))
                .andExpect(jsonPath("$.productoId").value("PROD-101"))
                .andExpect(jsonPath("$.nombre").value("Zapatillas Velocity X"))
                .andExpect(jsonPath("$.precio").value(249.0))
                .andExpect(jsonPath("$.variante.sku").value("ZAPVEL-38-NEG"))
                .andExpect(jsonPath("$.variante.talla").value("38"))
                .andExpect(jsonPath("$.variante.color").value("Negro"));
    }

    @Test
    @DisplayName("Una etiqueta de un articulo agotado se resuelve igual, marcada como agotada")
    void escanearUnArticuloAgotadoLoMarcaAgotado() throws Exception {
        mvc.perform(get("/api/v1/retail/catalogo/codigo-barras/{codigo}", CODIGO_AGOTADO)
                        .with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variante.sku").value("ZAPTRA-42-GRI"))
                .andExpect(jsonPath("$.variante.disponibilidad").value("AGOTADO_TOTAL"))
                // Hay oferta vigente: se cobra el precio promocional, no el regular.
                .andExpect(jsonPath("$.variante.precio").value(279.0));
    }

    @Test
    @DisplayName("Un codigo que no existe en el catalogo responde 404 con su codigo de negocio")
    void codigoInexistenteResponde404() throws Exception {
        mvc.perform(get("/api/v1/retail/catalogo/codigo-barras/{codigo}", "7759999999999")
                        .with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("CODIGO_BARRAS_NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("Un codigo con formato imposible se trata como no encontrado, no como error interno")
    void codigoMalFormadoResponde404() throws Exception {
        mvc.perform(get("/api/v1/retail/catalogo/codigo-barras/{codigo}", "abc")
                        .with(jwt().jwt(j -> j.subject(VENDEDOR))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CODIGO_BARRAS_NO_ENCONTRADO"));
    }
}
