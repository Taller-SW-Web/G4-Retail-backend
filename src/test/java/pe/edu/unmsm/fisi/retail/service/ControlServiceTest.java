package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.edu.unmsm.fisi.retail.dto.ControlDtos.CampanaVigenteResponse;
import pe.edu.unmsm.fisi.retail.dto.ControlDtos.ResumenControlResponse;
import pe.edu.unmsm.fisi.retail.dto.ControlDtos.VarianteCriticaResponse;
import pe.edu.unmsm.fisi.retail.dto.PedidoPickupResponse;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.productos.CampanasPort;
import pe.edu.unmsm.fisi.retail.integracion.productos.ProductosPort;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.Atributo;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.CampanaComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.DisponibilidadComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.DisponibilidadComercial.Estado;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.PaginaProductos;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.ProductoComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.VarianteComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.mock.CampanasMock;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Criterios de aceptación de F11 (RF-29, RF-30 y RF-31) sobre {@link ControlService}. */
@DisplayName("ControlService — torre de control F11")
class ControlServiceTest {

    private static final UUID VENDEDOR = UUID.randomUUID();
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-11-28T15:00:00Z"), ZoneOffset.UTC);

    private PickupService pickup;
    private ProductosPort productos;
    private CampanasPort campanas;
    private ControlService servicio;

    @BeforeEach
    void preparar() {
        pickup = mock(PickupService.class);
        productos = mock(ProductosPort.class);
        campanas = new CampanasMock();
        when(pickup.codigoSucursalDe(VENDEDOR)).thenReturn("TIENDA-MIRAFLORES");
        servicio = new ControlService(pickup, productos, campanas, RELOJ, 900, 120);
    }

    // ---------------------------------------------------------------- RF-30

    @Test
    @DisplayName("RF-30: lista solo los paquetes listos para recojo, del arribo más reciente al más antiguo")
    void pickupsListosOrdenados() {
        when(pickup.pendientes(VENDEDOR)).thenReturn(List.of(
                pedido("PED-1", "45678912", "2026-11-28T10:00:00Z", "LISTO_PARA_RECOJO"),
                pedido("PED-2", "72345678", "2026-11-28T14:00:00Z", "LISTO_PARA_RECOJO"),
                pedido("PED-3", "40123987", "2026-11-28T14:30:00Z", "EN_CAMINO_A_TIENDA")));

        List<PedidoPickupResponse> lista = servicio.pickupPendientes(VENDEDOR, null);

        assertThat(lista).extracting(PedidoPickupResponse::pedidoId).containsExactly("PED-2", "PED-1");
        assertThat(lista.get(0).anaquelUbicacion()).isEqualTo("Anaquel B-04");
    }

    @Test
    @DisplayName("RF-30: filtrar por DNI aísla la tarjeta del cliente")
    void pickupsPorDni() {
        when(pickup.pendientes(VENDEDOR)).thenReturn(List.of(
                pedido("PED-1", "45678912", "2026-11-28T10:00:00Z", "LISTO_PARA_RECOJO"),
                pedido("PED-2", "72345678", "2026-11-28T14:00:00Z", "LISTO_PARA_RECOJO")));

        assertThat(servicio.pickupPendientes(VENDEDOR, "72345678"))
                .extracting(PedidoPickupResponse::pedidoId).containsExactly("PED-2");
    }

    // ---------------------------------------------------------------- RF-29

    @Test
    @DisplayName("RF-29: AGOTADO va en rojo y STOCK_BAJO en ámbar (CRITICO), primero los agotados; los disponibles no aparecen")
    void stockCriticoPorNivel() {
        catalogo();

        List<VarianteCriticaResponse> lista = servicio.stockCritico(VENDEDOR);

        assertThat(lista).extracting(VarianteCriticaResponse::sku)
                .containsExactly("CHITER-41-NAR", "ZAPVEL-40-AZU");
        assertThat(lista.get(0).nivel()).isEqualTo("AGOTADO");
        assertThat(lista.get(1).nivel()).isEqualTo("CRITICO");
        assertThat(lista.get(1).talla()).isEqualTo("40");
        assertThat(lista.get(1).color()).isEqualTo("Azul");
        // X-P0-03: Productos no expone cantidades.
        assertThat(lista.get(1).stockTienda()).isNull();
    }

    @Test
    @DisplayName("RF-29: una segunda consulta dentro del TTL usa la caché y no vuelve a llamar a Productos")
    void stockCriticoEnCache() {
        catalogo();

        servicio.stockCritico(VENDEDOR);
        servicio.stockCritico(VENDEDOR);

        verify(productos, times(1)).consultarDisponibilidad(anyList());
    }

    // ---------------------------------------------------------------- RF-31

    @Test
    @DisplayName("RF-31: muestra las campañas RETAIL vigentes; las vencidas y las de otro canal no aparecen")
    void campanasVigentesRetail() {
        List<CampanaVigenteResponse> lista = servicio.campanasVigentes(VENDEDOR);

        assertThat(lista).extracting(CampanaVigenteResponse::campanaId)
                .containsExactlyInAnyOrder("CMP-2026-011", "CMP-2026-012", "CMP-2026-013")
                .doesNotContain("CMP-2026-007", "CMP-2026-014");
        assertThat(lista).extracting(CampanaVigenteResponse::codigoCupon).contains("VERANO2026", "DESCUENTO10");
    }

    @Test
    @DisplayName("RF-31: una campaña deja de aparecer en cuanto vence")
    void campanaVencida() {
        CampanasPort una = new CampanasPort() {
            @Override public List<CampanaComercial> listarCampanas() {
                return List.of(new CampanaComercial("C-1", "Flash", null, Set.of("RETAIL"), "PORCENTAJE", null,
                        "FLASH", null, null, OffsetDateTime.parse("2026-11-28T10:00:00Z"),
                        OffsetDateTime.parse("2026-11-28T14:59:59Z")));
            }
            @Override public String origen() { return "PRUEBA"; }
        };
        ControlService conVencida = new ControlService(pickup, productos, una, RELOJ, 900, 120);

        assertThat(conVencida.campanasVigentes(VENDEDOR)).isEmpty();
    }

    // ---------------------------------------------------------------- Resumen

    @Test
    @DisplayName("Resumen: cuenta pickups, stock crítico y campañas")
    void resumenCuenta() {
        catalogo();
        when(pickup.pendientes(VENDEDOR)).thenReturn(List.of(
                pedido("PED-1", "45678912", "2026-11-28T10:00:00Z", "LISTO_PARA_RECOJO")));

        ResumenControlResponse resumen = servicio.resumen(VENDEDOR);

        assertThat(resumen.pickupPendientes()).isEqualTo(1);
        assertThat(resumen.stockCritico()).isEqualTo(2);
        assertThat(resumen.campanasVigentes()).isEqualTo(3);
        assertThat(resumen.fuentesNoDisponibles()).isEmpty();
    }

    @Test
    @DisplayName("Resumen: si Ventas no responde, su contador viaja en null y el resto sigue")
    void resumenConFuenteCaida() {
        catalogo();
        when(pickup.pendientes(VENDEDOR)).thenThrow(
                new ServicioExternoNoDisponibleException("VENTAS_NO_DISPONIBLE", "caído"));

        ResumenControlResponse resumen = servicio.resumen(VENDEDOR);

        assertThat(resumen.pickupPendientes()).isNull();
        assertThat(resumen.fuentesNoDisponibles()).containsExactly("VENTAS_G5");
        assertThat(resumen.stockCritico()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- datos

    private void catalogo() {
        ProductoComercial zapatilla = producto("PROD-101", "Zapatillas Velocity X");
        ProductoComercial chimpun = producto("PROD-112", "Chimpunes Terra FG");
        when(productos.listarProductos(any())).thenReturn(new PaginaProductos(List.of(zapatilla, chimpun), 1, 100, 2));
        when(productos.listarVariantes("PROD-101")).thenReturn(List.of(
                variante("ZAPVEL-39-NEG", "39", "Negro"), variante("ZAPVEL-40-AZU", "40", "Azul")));
        when(productos.listarVariantes("PROD-112")).thenReturn(List.of(variante("CHITER-41-NAR", "41", "Naranja")));
        when(productos.consultarDisponibilidad(anyList())).thenReturn(List.of(
                new DisponibilidadComercial("ZAPVEL-39-NEG", Estado.DISPONIBLE),
                new DisponibilidadComercial("ZAPVEL-40-AZU", Estado.STOCK_BAJO),
                new DisponibilidadComercial("CHITER-41-NAR", Estado.AGOTADO)));
    }

    private static ProductoComercial producto(String id, String nombre) {
        return new ProductoComercial(id, id.toLowerCase(), nombre, null, "CAT", "TIPO", "MARCA", true, null, List.of());
    }

    private static VarianteComercial variante(String sku, String talla, String color) {
        return new VarianteComercial("V-" + sku, sku,
                List.of(new Atributo("CAR-TALLA", "Talla", talla), new Atributo("CAR-COLOR", "Color", color)),
                List.of(), List.of());
    }

    private static PedidoPickupResponse pedido(String id, String dni, String arribo, String estado) {
        return new PedidoPickupResponse(id, "Cliente " + id, dni, "Anaquel B-04", arribo, estado, List.of());
    }
}
