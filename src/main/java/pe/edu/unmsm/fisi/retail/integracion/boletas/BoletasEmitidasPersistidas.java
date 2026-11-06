package pe.edu.unmsm.fisi.retail.integracion.boletas;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import pe.edu.unmsm.fisi.retail.repository.RegistroVentaRepository;

import java.util.Optional;

@Component
@Primary
@RequiredArgsConstructor
public class BoletasEmitidasPersistidas implements BoletasEmitidasPort {

    private final RegistroVentaRepository ventas;
    private final ObjectProvider<BoletasEmitidasMock> datosDePrueba;

    @Override
    public Optional<BoletaEmitida> buscarPorNumero(String numero, String tienda, boolean bloquear) {
        int correlativo = Integer.parseInt(numero.substring(5));
        return ventas.buscarBoleta(tienda, correlativo, bloquear).map(venta -> {
            var prendas = ventas.lineas(venta.id()).stream().map(linea -> new BoletaEmitida.Item(
                    linea.sku(), linea.sku(), linea.precioPagado(), linea.cantidad())).toList();
            return new BoletaEmitida(numero, venta.pedidoId(), venta.fecha().toInstant(), venta.estadoPedido(), prendas, venta.id());
        }).or(() -> Optional.ofNullable(datosDePrueba.getIfAvailable())
                .flatMap(mock -> mock.buscarPorNumero(numero, tienda, bloquear)));
    }
}
