package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pe.edu.unmsm.fisi.retail.dto.CatalogoResponse;
import pe.edu.unmsm.fisi.retail.dto.ProductoCatalogoResponse;
import pe.edu.unmsm.fisi.retail.integracion.productos.ProductosPort;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.FiltroCatalogo;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.PaginaProductos;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.Precio;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.ProductoComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.VarianteComercial;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RF-04 con G6 real: el mostrador ve el nombre de la marca y de la categoría, no su id (contrato §5.2),
 * y puede filtrar por ese nombre.
 */
@DisplayName("CatalogoService — nombres de marca y categoría de G6")
class CatalogoServiceNombresTest {

    private static final String MARCA_ID = "d05cc621-2d16-faa3-f620-19fb3173737c";
    private static final String CATEGORIA_ID = "3894c641-982c-5db5-a9d2-3897b16d9cb3";

    private ProductosPort productos;
    private CatalogoService servicio;

    @BeforeEach
    void preparar() {
        productos = mock(ProductosPort.class);
        // variantesDe es un método por defecto: el doble lo resuelve con listarVariantes, como el puerto.
        when(productos.variantesDe(any())).thenCallRealMethod();
        when(productos.nombresDeMarcas()).thenReturn(Map.of(MARCA_ID, "adidas"));
        when(productos.nombresDeCategorias()).thenReturn(Map.of(CATEGORIA_ID, "Medias"));
        when(productos.listarProductos(any())).thenReturn(new PaginaProductos(List.of(
                new ProductoComercial("PROD-1", "s", "adidas adi 23", null, CATEGORIA_ID, "T", MARCA_ID, true, null, List.of()),
                new ProductoComercial("PROD-2", "s", "Sin marca conocida", null, "CAT-X", "T", "MARCA-X", true, null, List.of())),
                1, 20, 2));
        when(productos.listarVariantes(any())).thenReturn(List.of());
        servicio = new CatalogoService(productos);
    }

    @Test
    @DisplayName("Muestra el nombre de la marca y de la categoría; si G6 no lo publica, deja el id")
    void muestraNombres() {
        CatalogoResponse respuesta = servicio.buscar(null, null, null, null, 0, 20);

        ProductoCatalogoResponse conNombre = respuesta.items().get(0);
        assertThat(conNombre.marca()).isEqualTo("adidas");
        assertThat(conNombre.categoria()).isEqualTo("Medias");
        assertThat(conNombre.disciplina()).isEqualTo("Medias");
        assertThat(respuesta.items().get(1).marca()).isEqualTo("MARCA-X");
    }

    @Test
    @DisplayName("Toma los precios de la página en un solo lote, sin consultar precio por SKU")
    void preciosEnUnLote() {
        when(productos.listarVariantes("PROD-1")).thenReturn(List.of(
                new VarianteComercial("V1", "SKU-1", List.of(), List.of(), List.of()),
                new VarianteComercial("V2", "SKU-2", List.of(), List.of(), List.of())));
        when(productos.preciosDe(any())).thenReturn(Map.of(
                "SKU-1", new Precio("SKU-1", new BigDecimal("80"), new BigDecimal("59"), "PEN")));

        CatalogoResponse respuesta = servicio.buscar(null, null, null, null, 0, 20);

        verify(productos).preciosDe(List.of("SKU-1", "SKU-2"));
        verify(productos, never()).consultarPrecio(any());
        assertThat(respuesta.items().get(0).variantes().get(0).precio()).isEqualByComparingTo("59");
        assertThat(respuesta.items().get(0).variantes().get(1).precio()).isNull();
    }

    @Test
    @DisplayName("Filtra por el nombre que ve el mostrador enviando a G6 el id que le corresponde")
    void filtraPorNombre() {
        servicio.buscar(null, "medias", null, "ADIDAS", 0, 20);

        ArgumentCaptor<FiltroCatalogo> filtro = ArgumentCaptor.forClass(FiltroCatalogo.class);
        verify(productos).listarProductos(filtro.capture());
        assertThat(filtro.getValue().marcaId()).isEqualTo(MARCA_ID);
        assertThat(filtro.getValue().categoriaId()).isEqualTo(CATEGORIA_ID);
    }

    @Test
    @DisplayName("Un filtro que ya es id, o que no figura entre los nombres, viaja tal cual")
    void filtroDesconocidoViajaTalCual() {
        servicio.buscar(null, null, null, MARCA_ID, 0, 20);

        ArgumentCaptor<FiltroCatalogo> filtro = ArgumentCaptor.forClass(FiltroCatalogo.class);
        verify(productos).listarProductos(filtro.capture());
        assertThat(filtro.getValue().marcaId()).isEqualTo(MARCA_ID);
    }
}
