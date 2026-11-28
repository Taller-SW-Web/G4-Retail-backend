package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.edu.unmsm.fisi.retail.dto.CatalogoResponse;
import pe.edu.unmsm.fisi.retail.dto.CuarentenaItemResponse;
import pe.edu.unmsm.fisi.retail.dto.VarianteResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.integracion.productos.ProductosPort;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.PaginaProductos;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.ProductoComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.VarianteComercial;
import pe.edu.unmsm.fisi.retail.model.EstadoIncidencia;
import pe.edu.unmsm.fisi.retail.model.IncidenciaInventario;
import pe.edu.unmsm.fisi.retail.model.PerfilTienda;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.model.TipoFalla;
import pe.edu.unmsm.fisi.retail.repository.IncidenciaInventarioRepository;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;
import pe.edu.unmsm.fisi.retail.service.outbox.EventoOutboxEnviado;
import pe.edu.unmsm.fisi.retail.service.outbox.TipoEventoOutbox;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tarea B3 (RF-33): bandeja de cuarentena, SKU bloqueados en el catálogo y confirmación de Inventory.
 */
@DisplayName("CuarentenaService — cuarentena y bloqueo en el POS (RF-33)")
class CuarentenaServiceTest {

    private static final UUID VENDEDOR = UUID.randomUUID();
    private static final String TIENDA = "TIENDA-MIRAFLORES";

    private IncidenciaInventarioRepository incidencias;
    private PersonalTiendaRepository personal;
    private CuarentenaService servicio;

    @BeforeEach
    void preparar() {
        incidencias = mock(IncidenciaInventarioRepository.class);
        personal = mock(PersonalTiendaRepository.class);
        servicio = new CuarentenaService(incidencias, personal, new CodigosSucursal(""));
        PersonalTienda perfil = new PersonalTienda();
        perfil.setUsuarioId(VENDEDOR);
        perfil.setTiendaId(TIENDA);
        perfil.setPerfilTienda(PerfilTienda.VENDEDOR);
        perfil.setActivo(true);
        when(personal.findByUsuarioIdAndActivoTrue(VENDEDOR)).thenReturn(Optional.of(perfil));
    }

    @Test
    @DisplayName("CA-RF33-03: la bandeja lista la incidencia con su número, SKU y motivo de retención")
    void bandejaDeLaTienda() {
        IncidenciaInventario incidencia = incidencia(EstadoIncidencia.PENDIENTE_REPORTE);
        when(incidencias.findByTiendaIdAndEstadoInOrderByFechaReporteDesc(TIENDA, EstadoIncidencia.CUARENTENA_ACTIVA))
                .thenReturn(List.of(incidencia));

        List<CuarentenaItemResponse> bandeja = servicio.listar(VENDEDOR);

        assertThat(bandeja).singleElement().satisfies(item -> {
            assertThat(item.incidenciaId()).isEqualTo(incidencia.getIdIncidencia().toString());
            assertThat(item.sku()).isEqualTo("CAM-PERU-M-BLA");
            assertThat(item.nombreProducto()).isEqualTo("Camiseta Perú");
            assertThat(item.tipoFalla()).isEqualTo(TipoFalla.COSTURA_ROTA);
            assertThat(item.estado()).isEqualTo(EstadoIncidencia.PENDIENTE_REPORTE);
            assertThat(item.fechaReporte()).isNotNull();
        });
    }

    @Test
    @DisplayName("Sin perfil activo no hay bandeja que mostrar (403)")
    void bandejaSinPerfil() {
        UUID otro = UUID.randomUUID();
        when(personal.findByUsuarioIdAndActivoTrue(otro)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.listar(otro)).isInstanceOf(AccesoDenegadoException.class);
    }

    @Test
    @DisplayName("RF-33: devuelve los SKU bloqueados de la tienda del vendedor (PENDIENTE_REPORTE y EN_CUARENTENA)")
    void skusBloqueadosDeLaTienda() {
        when(incidencias.skusConEstado(eq(TIENDA), anyCollection(), eq(EstadoIncidencia.CUARENTENA_ACTIVA)))
                .thenReturn(List.of("CAM-PERU-M-BLA"));

        assertThat(servicio.skusBloqueados(VENDEDOR, List.of("CAM-PERU-M-BLA", "ZAP-42")))
                .containsExactly("CAM-PERU-M-BLA");
    }

    @Test
    @DisplayName("Sin vendedor (o sin SKU) no consulta la cuarentena")
    void sinVendedorNoHayCuarentena() {
        assertThat(servicio.skusBloqueados(null, List.of("A"))).isEmpty();
        assertThat(servicio.skusBloqueados(VENDEDOR, List.of())).isEmpty();
        verifyNoInteractions(incidencias);
    }

    @Test
    @DisplayName("CA-RF33-01: el catálogo marca enCuarentena en la variante reportada y deja libres las demás")
    void catalogoMarcaLaCuarentena() {
        ProductosPort productos = mock(ProductosPort.class);
        when(productos.nombresDeMarcas()).thenReturn(Map.of());
        when(productos.nombresDeCategorias()).thenReturn(Map.of());
        when(productos.listarProductos(any())).thenReturn(new PaginaProductos(List.of(
                new ProductoComercial("PROD-1", "s", "Camiseta Perú", null, "C", "T", "M", true, null, List.of())),
                1, 20, 1));
        when(productos.variantesDe(List.of("PROD-1"))).thenReturn(Map.of(
                "PROD-1", List.of(variante("CAM-PERU-M-BLA"), variante("CAM-PERU-L-BLA"))));
        when(productos.consultarDisponibilidad(any())).thenReturn(List.of());
        when(productos.consultarPrecio(any())).thenReturn(Optional.empty());
        when(incidencias.skusConEstado(eq(TIENDA), anyCollection(), eq(EstadoIncidencia.CUARENTENA_ACTIVA)))
                .thenReturn(List.of("CAM-PERU-M-BLA"));
        CatalogoService catalogo = new CatalogoService(productos);
        catalogo.setCuarentena(servicio);

        CatalogoResponse respuesta = catalogo.buscar(null, null, null, null, 0, 20, VENDEDOR);

        List<VarianteResponse> variantes = respuesta.items().get(0).variantes();
        assertThat(variantes).filteredOn(VarianteResponse::enCuarentena)
                .extracting(VarianteResponse::sku).containsExactly("CAM-PERU-M-BLA");
    }

    @Test
    @DisplayName("§5.12: cuando Inventory acepta el reporte, la incidencia pasa a EN_CUARENTENA con su id de G6")
    void inventoryAceptaElReporte() {
        IncidenciaInventario incidencia = incidencia(EstadoIncidencia.PENDIENTE_REPORTE);
        when(incidencias.findById(incidencia.getIdIncidencia())).thenReturn(Optional.of(incidencia));

        servicio.alEnviarseAInventory(new EventoOutboxEnviado(TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA,
                incidencia.getIdIncidencia().toString(), "INC-INV-0001"));

        assertThat(incidencia.getEstado()).isEqualTo(EstadoIncidencia.EN_CUARENTENA);
        assertThat(incidencia.getIncidenciaInventoryId()).isEqualTo("INC-INV-0001");
        assertThat(incidencia.enCuarentenaActiva()).isTrue();
        verify(incidencias).save(incidencia);
    }

    @Test
    @DisplayName("Una incidencia ya liquidada por un acta no vuelve a la cuarentena al llegar la confirmación")
    void noReabreUnaIncidenciaLiquidada() {
        IncidenciaInventario incidencia = incidencia(EstadoIncidencia.DERIVADO_ALMACEN);
        when(incidencias.findById(incidencia.getIdIncidencia())).thenReturn(Optional.of(incidencia));

        servicio.alEnviarseAInventory(new EventoOutboxEnviado(TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA,
                incidencia.getIdIncidencia().toString(), "INC-INV-0002"));

        assertThat(incidencia.getEstado()).isEqualTo(EstadoIncidencia.DERIVADO_ALMACEN);
    }

    @Test
    @DisplayName("Un error al confirmar no se propaga al despachador del outbox")
    void errorAlConfirmarNoSePropaga() {
        when(incidencias.findById(any())).thenThrow(new RuntimeException("BD caída"));

        servicio.alEnviarseAInventory(new EventoOutboxEnviado(TipoEventoOutbox.INCIDENCIA_INVENTARIO_REPORTADA,
                UUID.randomUUID().toString(), "INC-INV-0003"));

        verify(incidencias, never()).save(any());
    }

    private static IncidenciaInventario incidencia(EstadoIncidencia estado) {
        return IncidenciaInventario.builder()
                .idIncidencia(UUID.randomUUID())
                .tiendaId(TIENDA)
                .vendedorReportaId(VENDEDOR)
                .sku("CAM-PERU-M-BLA")
                .nombreProducto("Camiseta Perú")
                .codigoBarras("7759876543210")
                .tipoFalla(TipoFalla.COSTURA_ROTA)
                .detalleObservacion("Costura dañada")
                .estado(estado)
                .fechaReporte(OffsetDateTime.now())
                .build();
    }

    private static VarianteComercial variante(String sku) {
        return new VarianteComercial("VAR-" + sku, sku, null, null, null);
    }
}
