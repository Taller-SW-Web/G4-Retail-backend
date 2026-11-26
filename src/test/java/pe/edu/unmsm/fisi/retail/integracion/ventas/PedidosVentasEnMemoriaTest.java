package pe.edu.unmsm.fisi.retail.integracion.ventas;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** El doble en memoria imita la búsqueda 1.4c de G5 (contrato v0.5.2, §5.6): solo pedidos de tienda. */
@DisplayName("PedidosVentasEnMemoria — búsqueda por documento (RF-16-DOC)")
class PedidosVentasEnMemoriaTest {

    private final PedidosVentasEnMemoria ventas = new PedidosVentasEnMemoria();

    @Test
    @DisplayName("Devuelve los pedidos RETAIL del titular y deja fuera los de delivery de otros canales")
    void soloPedidosDeTienda() {
        assertThat(ventas.buscarPorDocumento("72345678"))
                .extracting(PedidoResumenVentas::pedidoId)
                .containsExactly("PED-2026-00412");
    }

    @Test
    @DisplayName("Incluye los pedidos de otros canales con recojo en tienda y trae nombre y total")
    void incluyeRecojoEnTienda() {
        assertThat(ventas.buscarPorDocumento("45678912")).singleElement().satisfies(p -> {
            assertThat(p.pedidoId()).isEqualTo("PED-2026-00405");
            assertThat(p.canal()).isEqualTo("MARKETPLACE");
            assertThat(p.nombreCliente()).isEqualTo("María González");
            assertThat(p.total()).isEqualByComparingTo("139.90");
            assertThat(p.moneda()).isEqualTo("PEN");
        });
    }

    @Test
    @DisplayName("Un documento sin pedidos o nulo no devuelve nada")
    void sinResultados() {
        assertThat(ventas.buscarPorDocumento("99999999")).isEmpty();
        assertThat(ventas.buscarPorDocumento(null)).isEmpty();
    }
}
