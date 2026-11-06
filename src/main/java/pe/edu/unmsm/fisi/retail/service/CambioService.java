package pe.edu.unmsm.fisi.retail.service;

import pe.edu.unmsm.fisi.retail.dto.RegistroCambioRequest;
import pe.edu.unmsm.fisi.retail.dto.ValeCompraResponse;
import pe.edu.unmsm.fisi.retail.dto.ValidarCambioResponse;

import java.util.UUID;

/**
 * Cambios de prenda y vales de compra en mostrador (F9: RF-23, RF-24 y RF-25).
 *
 * <p>Es 100% autonomo de Retail: no llama a Ventas ni a Productos y no mueve stock.</p>
 */
public interface CambioService {

    /**
     * Valida un comprobante para un cambio (RF-23): que exista, que el pedido este pagado o entregado,
     * cuantos dias van desde la compra y cuantas unidades de cada prenda quedan por cambiar.
     */
    ValidarCambioResponse validar(UUID usuarioId, String boletaNumero);

    /**
     * Registra el cambio con su inspeccion fisica (RF-24) y emite el vale de compra (RF-25).
     *
     * @param usuarioId      vendedor autenticado, el {@code sub} del token
     * @param idempotencyKey repetir la misma clave devuelve el mismo vale, en vez de emitir otro
     */
    ValeCompraResponse registrar(UUID usuarioId, String idempotencyKey, RegistroCambioRequest peticion);
}
