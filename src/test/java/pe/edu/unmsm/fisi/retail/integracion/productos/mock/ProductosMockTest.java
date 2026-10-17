package pe.edu.unmsm.fisi.retail.integracion.productos.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.DisponibilidadComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.FiltroCatalogo;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.PaginaProductos;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.VarianteComercial;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas del mock de Productos (G6).
 *
 * <p>Criterios de aceptacion que se verifican, tomados de la spec F2: el lector de codigo de barras
 * es la entrada prioritaria y debe resolver a un SKU vendible; un codigo desconocido no debe
 * confundirse con un producto agotado; y el catalogo debe poder filtrarse y paginarse.</p>
 */
class ProductosMockTest {

    private ProductosMock mock;

    @BeforeEach
    void prepararMock() {
        mock = new ProductosMock(new ObjectMapper());
    }

    @Test
    @DisplayName("La semilla cumple lo que pide el contrato: entre 15 y 20 productos")
    void laSemillaTieneElVolumenPedido() {
        PaginaProductos pagina = mock.listarProductos(FiltroCatalogo.primeraPagina(100));

        assertThat(pagina.total()).isBetween(15L, 20L);
        assertThat(pagina.items()).isNotEmpty();
    }

    @Test
    @DisplayName("Cada SKU tiene un codigo de barras unico y valido")
    void cadaSkuTieneCodigoDeBarrasUnico() {
        PaginaProductos pagina = mock.listarProductos(FiltroCatalogo.primeraPagina(100));

        List<String> codigos = pagina.items().stream()
                .flatMap(p -> mock.listarVariantes(p.productId()).stream())
                .map(VarianteComercial::sku)
                .map(sku -> mock.consultarPrecio(sku).orElseThrow().sku())
                .toList();

        assertThat(codigos).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Un codigo de barras conocido se resuelve al SKU de su variante")
    void resuelveUnCodigoDeBarrasConocido() {
        Optional<String> sku = mock.resolverCodigoBarras("7750101000012");

        assertThat(sku).contains("ZAPVEL-38-NEG");
    }

    @Test
    @DisplayName("Un codigo desconocido devuelve vacio, nunca un producto equivocado")
    void codigoDesconocidoDevuelveVacio() {
        assertThat(mock.resolverCodigoBarras("0000000000000")).isEmpty();
        assertThat(mock.resolverCodigoBarras(null)).isEmpty();
    }

    @Test
    @DisplayName("La busqueda ignora tildes y mayusculas, como la pantalla de venta")
    void laBusquedaIgnoraTildesYMayusculas() {
        PaginaProductos conTilde = mock.listarProductos(
                new FiltroCatalogo("BALÓN", null, null, 1, 50));

        assertThat(conTilde.items()).isNotEmpty();
        assertThat(conTilde.items().getFirst().name()).containsIgnoringCase("balon");
    }

    @Test
    @DisplayName("Filtra por categoria")
    void filtraPorCategoria() {
        PaginaProductos running = mock.listarProductos(
                new FiltroCatalogo(null, "CAT-RUNNING", null, 1, 50));

        assertThat(running.items()).isNotEmpty();
        assertThat(running.items()).allSatisfy(p -> assertThat(p.categoriaId()).isEqualTo("CAT-RUNNING"));
    }

    @Test
    @DisplayName("Pagina empezando en 1, como G6, y no repite elementos entre paginas")
    void paginaEmpezandoEnUno() {
        PaginaProductos primera = mock.listarProductos(new FiltroCatalogo(null, null, null, 1, 5));
        PaginaProductos segunda = mock.listarProductos(new FiltroCatalogo(null, null, null, 2, 5));

        assertThat(primera.items()).hasSize(5);
        assertThat(segunda.items()).hasSize(5);
        assertThat(primera.items()).doesNotContainAnyElementsOf(segunda.items());
        assertThat(primera.total()).isEqualTo(segunda.total());
    }

    @Test
    @DisplayName("Una pagina mas alla del total sale vacia, sin reventar")
    void paginaFueraDeRangoSaleVacia() {
        PaginaProductos pagina = mock.listarProductos(new FiltroCatalogo(null, null, null, 99, 20));

        assertThat(pagina.items()).isEmpty();
        assertThat(pagina.total()).isPositive();
    }

    @Test
    @DisplayName("El precio efectivo es la oferta cuando existe")
    void elPrecioEfectivoPrefiereLaOferta() {
        String skuEnOferta = mock.resolverCodigoBarras("7750102000059").orElseThrow();

        var precio = mock.consultarPrecio(skuEnOferta).orElseThrow();

        assertThat(precio.currency()).isEqualTo("PEN");
        assertThat(precio.precioOferta()).isNotNull();
        assertThat(precio.precioEfectivo()).isEqualByComparingTo(precio.precioOferta());
        assertThat(precio.precioEfectivo()).isLessThan(precio.precioRegular());
    }

    @Test
    @DisplayName("El precio se modela con BigDecimal, no con double")
    void elPrecioEsBigDecimal() {
        var precio = mock.consultarPrecio("ZAPVEL-38-NEG").orElseThrow();

        assertThat(precio.precioRegular()).isInstanceOf(BigDecimal.class);
        assertThat(precio.precioRegular()).isEqualByComparingTo(new BigDecimal("249.00"));
    }

    @Test
    @DisplayName("La disponibilidad responde por cada SKU pedido")
    void devuelveDisponibilidadPorSku() {
        List<DisponibilidadComercial> estados = mock.consultarDisponibilidad(
                List.of("ZAPVEL-38-NEG", "SKU-QUE-NO-EXISTE"));

        assertThat(estados).hasSize(2);
        assertThat(estados.getFirst().status()).isEqualTo(DisponibilidadComercial.Estado.DISPONIBLE);
        assertThat(estados.getLast().status()).isEqualTo(DisponibilidadComercial.Estado.INDETERMINADO);
    }

    @Test
    @DisplayName("Las variantes traen talla y color, que es lo que ve el vendedor")
    void lasVariantesTraenTallaYColor() {
        List<VarianteComercial> variantes = mock.listarVariantes("PROD-101");

        assertThat(variantes).isNotEmpty();
        VarianteComercial primera = variantes.getFirst();
        assertThat(primera.atributo("Talla")).isPresent();
        assertThat(primera.atributo("Color")).isPresent();
    }
}
