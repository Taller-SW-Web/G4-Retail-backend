package pe.edu.unmsm.fisi.retail.integracion.ventas;

import pe.edu.unmsm.fisi.retail.integracion.ventas.PedidoVentas.Contacto;
import pe.edu.unmsm.fisi.retail.integracion.ventas.PedidoVentas.Envio;
import pe.edu.unmsm.fisi.retail.integracion.ventas.PedidoVentas.Historial;
import pe.edu.unmsm.fisi.retail.integracion.ventas.PedidoVentas.Item;
import pe.edu.unmsm.fisi.retail.integracion.ventas.PedidoVentas.Pago;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pedidos de ejemplo, en memoria, con el mismo formato que Ventas (G5).
 *
 * <p>Se usa en las pruebas y cuando el backend corre sin credenciales de servicio de G7. Cubre los
 * seis estados oficiales de Ventas y los tres canales.</p>
 */
public class PedidosVentasEnMemoria implements PedidosVentasPort {

    private final Map<String, PedidoVentas> pedidos;

    public PedidosVentasEnMemoria() {
        Contacto juan = new Contacto("Juan Pérez Torres", "DNI", "72345678");
        Contacto maria = new Contacto("María González", "DNI", "45678912");
        Contacto empresa = new Contacto("Club Deportivo Los Andes S.A.C.", "RUC", "20123456789");

        pedidos = List.of(
                pedido("PED-2026-00412", "RETAIL", juan, "EFECTIVO", new Envio("TIENDA", "TIENDA-MIRAFLORES"), 2,
                        List.of(new Item("ZAP-RUN-41-BLANC", "Zapatilla Running Pegasus 40", 1, new BigDecimal("459.90")),
                                new Item("MED-DEP-M-NEGRO", "Medias Deportivas Pack x3", 2, new BigDecimal("29.90"))),
                        "PAGADO", "ENTREGADO"),
                pedido("PED-2026-00398", "MARKETPLACE", juan, "TARJETA", new Envio("DOMICILIO", null), 30,
                        List.of(new Item("CAM-PERU-M-BLANC", "Camiseta Selección 2026", 1, new BigDecimal("249.90"))),
                        "CREADO", "PAGADO", "EN_PREPARACION", "DESPACHADO"),
                pedido("PED-2026-00377", "CHATBOT", juan, "YAPE", new Envio("DOMICILIO", null), 120,
                        List.of(new Item("BAL-FUT-5-BLANC", "Balón de Fútbol Match N.° 5", 1, new BigDecimal("119.90"))),
                        "CREADO", "PAGADO", "ANULADO"),
                pedido("PED-2026-00405", "MARKETPLACE", maria, "TARJETA", new Envio("RECOJO_TIENDA", "TIENDA-MIRAFLORES"), 20,
                        List.of(new Item("LEG-TRA-S-NEGRO", "Leggings Training Compresión", 1, new BigDecimal("139.90"))),
                        "CREADO", "PAGADO", "EN_PREPARACION"),
                pedido("PED-2026-00415", "RETAIL", empresa, "TARJETA_POS", new Envio("TIENDA", "TIENDA-MIRAFLORES"), 1,
                        List.of(new Item("CAS-OUT-L-VERDE", "Casaca Cortaviento Trail", 4, new BigDecimal("289.90"))),
                        "PAGADO")
        ).stream().collect(Collectors.toUnmodifiableMap(PedidoVentas::pedidoId, Function.identity()));
    }

    @Override
    public Optional<PedidoVentas> consultar(String pedidoId) {
        return Optional.ofNullable(pedidos.get(pedidoId));
    }

    @Override
    public String origen() {
        return "EN_MEMORIA";
    }

    /**
     * Arma un pedido creado hace {@code haceHoras}. Los estados se reparten en ese lapso, así que
     * ninguno queda en el futuro; el último es el estado actual.
     */
    private static PedidoVentas pedido(String id, String canal, Contacto contacto, String metodoPago, Envio envio,
                                       int haceHoras, List<Item> items, String... estados) {
        Instant creado = Instant.now().minus(haceHoras, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        long pasoMinutos = haceHoras * 60L / estados.length;
        List<Historial> historial = new ArrayList<>();
        for (int i = 0; i < estados.length; i++) {
            String motivo = "ANULADO".equals(estados[i]) ? "Solicitado por el cliente" : null;
            historial.add(new Historial(estados[i], creado.plus(i * pasoMinutos, ChronoUnit.MINUTES).toString(), motivo));
        }
        BigDecimal total = items.stream()
                .map(it -> it.precioUnitario().multiply(BigDecimal.valueOf(it.cantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String actual = estados[estados.length - 1];
        String ultima = historial.get(historial.size() - 1).fecha();
        return new PedidoVentas(id, canal, actual, "VENTA", contacto,
                new Pago(total, "PEN", metodoPago, "CONFIRMADO"), envio, items,
                creado.toString(), ultima, historial);
    }
}
