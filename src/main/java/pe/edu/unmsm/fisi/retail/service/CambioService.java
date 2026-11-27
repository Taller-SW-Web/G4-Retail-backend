package pe.edu.unmsm.fisi.retail.service;

import pe.edu.unmsm.fisi.retail.dto.CambioDirectoRequest;
import pe.edu.unmsm.fisi.retail.dto.CambioDirectoResponse;
import pe.edu.unmsm.fisi.retail.dto.ValidarCambioResponse;

import java.util.UUID;

/**
 * Cambio directo de prenda en mostrador (F9: RF-23, RF-24 y RF-25, contrato 5.9).
 *
 * <p>Desde la Semana 14 reemplaza al vale: el cliente devuelve una prenda y se lleva otra en el
 * momento, pagando la diferencia si cuesta mas.</p>
 */
public interface CambioService {

    /**
     * Valida un comprobante para un cambio (RF-23): que exista, que el pedido este pagado o entregado,
     * cuantos dias van desde la compra y cuantas unidades de cada prenda quedan por cambiar.
     */
    ValidarCambioResponse validar(UUID usuarioId, String boletaNumero);

    /**
     * Registra el cambio directo con su inspeccion (RF-24) en una sola transaccion (RF-25).
     *
     * @param usuarioId      vendedor autenticado, el {@code sub} del token
     * @param idempotencyKey repetir la misma clave devuelve el mismo cambio, sin consumir mas unidades
     */
    CambioDirectoResponse registrar(UUID usuarioId, String idempotencyKey, CambioDirectoRequest peticion);

    /**
     * Reenvia a Ventas los pedidos de los cambios {@code PENDIENTE}, con su misma clave.
     *
     * @return cuantos pasaron a {@code CONFIRMADO}
     */
    int reintentarPedidosPendientes();
}
