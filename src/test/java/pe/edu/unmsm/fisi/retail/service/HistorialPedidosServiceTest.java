package pe.edu.unmsm.fisi.retail.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.edu.unmsm.fisi.retail.dto.DetallePedidoHistoricoResponse;
import pe.edu.unmsm.fisi.retail.dto.PaginaPedidosResponse;
import pe.edu.unmsm.fisi.retail.dto.PedidoResumenDto;
import pe.edu.unmsm.fisi.retail.exception.ParametroInvalidoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.integracion.ventas.PedidoVentas;
import pe.edu.unmsm.fisi.retail.integracion.ventas.PedidosVentasPort;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Consulta histórica de pedidos (F6). Criterios de RF-16 y RF-17: lista del más reciente al más
 * antiguo, historial en orden cronológico ascendente, fechas en hora de Lima y 404 si no hay pedidos.
 */
@DisplayName("HistorialPedidosService — F6 (RF-16, RF-17)")
class HistorialPedidosServiceTest {

    private PedidosVentasPort ventas;
    private HistorialPedidosService servicio;

    @BeforeEach
    void preparar() {
        ventas = mock(PedidosVentasPort.class);
        when(ventas.origen()).thenReturn("PRUEBA");
        servicio = new HistorialPedidosService(ventas);
    }

    private static PedidoVentas pedido(String id, String creado, String estado, List<PedidoVentas.Historial> historial) {
        return new PedidoVentas(id, "RETAIL", estado, "VENTA",
                new PedidoVentas.Contacto("Juan Pérez", "DNI", "72345678"),
                new PedidoVentas.Pago(new BigDecimal("489.80"), "PEN", "EFECTIVO", "CONFIRMADO"),
                new PedidoVentas.Envio("TIENDA", "TIENDA-MIRAFLORES"),
                List.of(new PedidoVentas.Item("ZAP-RUN-41-BLANC", "Zapatilla Running", 2, new BigDecimal("229.95"))),
                creado, creado, historial);
    }

    @Test
    @DisplayName("RF-16: por DNI lista los pedidos del más reciente al más antiguo")
    void porDocumentoOrdenaDescendente() {
        when(ventas.buscarPorDocumento("72345678")).thenReturn(List.of(
                pedido("PED-2026-00001", "2026-10-01T10:00:00Z", "ENTREGADO", List.of()),
                pedido("PED-2026-00003", "2026-11-05T10:00:00Z", "PAGADO", List.of()),
                pedido("PED-2026-00002", "2026-10-20T10:00:00Z", "ANULADO", List.of())));

        PaginaPedidosResponse pagina = servicio.buscar(null, "72345678", 0, 20);

        assertThat(pagina.items()).extracting(PedidoResumenDto::pedidoId)
                .containsExactly("PED-2026-00003", "PED-2026-00002", "PED-2026-00001");
        assertThat(pagina.total()).isEqualTo(3);
        assertThat(pagina.items().get(0).estadoComercial()).isEqualTo("PAGADO");
        assertThat(pagina.items().get(0).total()).isEqualByComparingTo("489.80");
    }

    @Test
    @DisplayName("RF-16: por código devuelve el pedido con la fecha en hora de Lima")
    void porCodigo() {
        when(ventas.consultar("PED-2026-00412"))
                .thenReturn(Optional.of(pedido("PED-2026-00412", "2026-11-06T15:30:00Z", "PAGADO", List.of())));

        PaginaPedidosResponse pagina = servicio.buscar("PED-2026-00412", null, 0, 20);

        assertThat(pagina.items()).hasSize(1);
        assertThat(pagina.items().get(0).fechaCreacion()).isEqualTo("2026-11-06T10:30:00-05:00");
    }

    @Test
    @DisplayName("RF-16: un código inexistente responde PEDIDO_NO_ENCONTRADO")
    void codigoInexistente() {
        when(ventas.consultar("PED-2026-99999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.buscar("PED-2026-99999", null, 0, 20))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .extracting("codigo").isEqualTo("PEDIDO_NO_ENCONTRADO");
    }

    @Test
    @DisplayName("Con código y documento, el pedido debe ser de ese comprador")
    void codigoYDocumentoDeOtroComprador() {
        when(ventas.consultar("PED-2026-00412"))
                .thenReturn(Optional.of(pedido("PED-2026-00412", "2026-11-06T15:30:00Z", "PAGADO", List.of())));

        assertThatThrownBy(() -> servicio.buscar("PED-2026-00412", "45678912", 0, 20))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    @DisplayName("Sin filtros responde PARAMETRO_OBLIGATORIO; un filtro vacío cuenta como ausente")
    void sinFiltros() {
        assertThatThrownBy(() -> servicio.buscar(" ", "", 0, 20))
                .isInstanceOf(ParametroInvalidoException.class)
                .extracting("codigo").isEqualTo("PARAMETRO_OBLIGATORIO");
    }

    @Test
    @DisplayName("Un documento que no es DNI ni RUC responde PARAMETRO_INVALIDO")
    void documentoInvalido() {
        assertThatThrownBy(() -> servicio.buscar(null, "1234", 0, 20))
                .isInstanceOf(ParametroInvalidoException.class)
                .extracting("campo").isEqualTo("documentoCliente");
    }

    @Test
    @DisplayName("Paginación fuera de rango responde PARAMETRO_INVALIDO")
    void paginacionInvalida() {
        assertThatThrownBy(() -> servicio.buscar(null, "72345678", -1, 20))
                .isInstanceOf(ParametroInvalidoException.class).extracting("campo").isEqualTo("page");
        assertThatThrownBy(() -> servicio.buscar(null, "72345678", 0, 101))
                .isInstanceOf(ParametroInvalidoException.class).extracting("campo").isEqualTo("size");
    }

    @Test
    @DisplayName("Pagina el resultado con page desde 0")
    void pagina() {
        when(ventas.buscarPorDocumento("72345678")).thenReturn(List.of(
                pedido("PED-2026-00001", "2026-10-01T10:00:00Z", "ENTREGADO", List.of()),
                pedido("PED-2026-00002", "2026-10-02T10:00:00Z", "ENTREGADO", List.of()),
                pedido("PED-2026-00003", "2026-10-03T10:00:00Z", "ENTREGADO", List.of())));

        PaginaPedidosResponse segunda = servicio.buscar(null, "72345678", 1, 2);

        assertThat(segunda.items()).extracting(PedidoResumenDto::pedidoId).containsExactly("PED-2026-00001");
        assertThat(segunda.total()).isEqualTo(3);
        assertThat(servicio.buscar(null, "72345678", 5, 2).items()).isEmpty();
    }

    @Test
    @DisplayName("RF-17: el historial sale en orden cronológico ascendente y en hora de Lima")
    void historialAscendenteEnLima() {
        when(ventas.consultar("PED-2026-00398")).thenReturn(Optional.of(pedido("PED-2026-00398",
                "2026-11-01T13:00:00Z", "DESPACHADO", List.of(
                        new PedidoVentas.Historial("DESPACHADO", "2026-11-02T20:00:00Z", null),
                        new PedidoVentas.Historial("CREADO", "2026-11-01T13:00:00Z", null),
                        new PedidoVentas.Historial("PAGADO", "2026-11-01T13:05:00Z", null)))));

        DetallePedidoHistoricoResponse detalle = servicio.detalle("PED-2026-00398");

        assertThat(detalle.historialEstados()).extracting(DetallePedidoHistoricoResponse.EventoEstado::estado)
                .containsExactly("CREADO", "PAGADO", "DESPACHADO");
        assertThat(detalle.historialEstados().get(0).fecha()).isEqualTo("2026-11-01T08:00:00-05:00");
        assertThat(detalle.estadoComercial()).isEqualTo("DESPACHADO");
        assertThat(detalle.modalidad()).isEqualTo("TIENDA");
        assertThat(detalle.tiendaId()).isEqualTo("TIENDA-MIRAFLORES");
        assertThat(detalle.cliente().numeroDocumento()).isEqualTo("72345678");
    }

    @Test
    @DisplayName("RF-17: cada prenda trae SKU, nombre, cantidad, precio y subtotal")
    void lineasConSubtotal() {
        when(ventas.consultar("PED-2026-00412"))
                .thenReturn(Optional.of(pedido("PED-2026-00412", "2026-11-06T15:30:00Z", "PAGADO", List.of())));

        DetallePedidoHistoricoResponse.Linea linea = servicio.detalle("PED-2026-00412").lineas().get(0);

        assertThat(linea.sku()).isEqualTo("ZAP-RUN-41-BLANC");
        assertThat(linea.nombre()).isEqualTo("Zapatilla Running");
        assertThat(linea.cantidad()).isEqualTo(2);
        assertThat(linea.subtotal()).isEqualByComparingTo("459.90");
    }

    @Test
    @DisplayName("RF-17: el detalle de un pedido inexistente responde PEDIDO_NO_ENCONTRADO")
    void detalleInexistente() {
        when(ventas.consultar("PED-2026-99999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.detalle("PED-2026-99999"))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }
}
