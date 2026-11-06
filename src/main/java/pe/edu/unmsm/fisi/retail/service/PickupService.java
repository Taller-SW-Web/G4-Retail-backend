package pe.edu.unmsm.fisi.retail.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pe.edu.unmsm.fisi.retail.dto.EntregaPickupRequest;
import pe.edu.unmsm.fisi.retail.dto.EntregaPickupResponse;
import pe.edu.unmsm.fisi.retail.dto.PedidoPickupResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.exception.ConflictoException;
import pe.edu.unmsm.fisi.retail.exception.RecursoNoEncontradoException;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.integracion.ventas.PickupVentasPort;
import pe.edu.unmsm.fisi.retail.model.PersonalTienda;
import pe.edu.unmsm.fisi.retail.repository.PersonalTiendaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Bandeja y entrega de pedidos pickup en tienda (F7): RF-18 y RF-19.
 *
 * <p>El pedido y su estado son de Ventas (G5): la bandeja se lee de Ventas y la entrega se le
 * informa a Ventas, que lo pasa a {@code ENTREGADO}. Lo que sí es de Retail es la verificación en
 * mostrador de quién recoge:</p>
 * <ul>
 *   <li>Si recoge el titular, su DNI debe coincidir con el del pedido.</li>
 *   <li>Si recoge un tercero, el vendedor debe haber verificado la carta poder.</li>
 * </ul>
 *
 * <p>Límite estricto: solo entrega paquetes ya pagados. No crea pedidos ni cobra dinero.</p>
 */
@Slf4j
@Service
public class PickupService {

    static final String LISTO_PARA_RECOJO = "LISTO_PARA_RECOJO";

    private final PickupVentasPort ventas;
    private final PersonalTiendaRepository personalTiendaRepository;
    private final String tiendaPorDefecto;

    public PickupService(PickupVentasPort ventas,
                         PersonalTiendaRepository personalTiendaRepository,
                         @Value("${retail.tienda.codigo-por-defecto:TIENDA-MIRAFLORES}") String tiendaPorDefecto) {
        this.ventas = ventas;
        this.personalTiendaRepository = personalTiendaRepository;
        this.tiendaPorDefecto = tiendaPorDefecto;
    }

    /** RF-18: pedidos pickup de la tienda del vendedor que todavía no se entregan. */
    public List<PedidoPickupResponse> pendientes(UUID usuarioId) {
        return ventas.listarPendientes(tiendaDe(usuarioId));
    }

    /**
     * RF-19: verifica a quien recoge, informa la entrega a Ventas y devuelve la constancia.
     *
     * <p>{@code 404} si el pedido no figura en la bandeja de la tienda; {@code 409} si todavía no está
     * listo para recojo; {@code 422} si el DNI no es del titular o falta la carta poder del tercero.</p>
     *
     * @param usuarioId vendedor que entrega, tomado del token
     */
    public EntregaPickupResponse entregar(String pedidoId, UUID usuarioId, EntregaPickupRequest peticion) {
        String tiendaId = tiendaDe(usuarioId);
        PedidoPickupResponse pedido = ventas.listarPendientes(tiendaId).stream()
                .filter(p -> p.pedidoId().equals(pedidoId))
                .findFirst()
                .orElseThrow(() -> new RecursoNoEncontradoException("PEDIDO_NO_ENCONTRADO",
                        "El pedido " + pedidoId + " no figura en la bandeja de la tienda"));

        if (!LISTO_PARA_RECOJO.equals(pedido.estado())) {
            throw new ConflictoException("PEDIDO_NO_LISTO_PARA_RECOJO",
                    "El paquete no figura en estado listo para recojo");
        }

        if (peticion.esTerceroAutorizado()) {
            if (!Boolean.TRUE.equals(peticion.cartaPoderVerificada())) {
                throw new ReglaNegocioException("CARTA_PODER_REQUERIDA",
                        "Un tercero solo puede recoger si presenta la carta poder del titular");
            }
        } else if (tieneDni(pedido) && !pedido.clienteDni().equals(peticion.dniRecoge())) {
            throw new ReglaNegocioException("DNI_NO_COINCIDE_CON_TITULAR",
                    "El DNI presentado no corresponde al titular del pedido");
        }

        EntregaPickupResponse constancia = ventas.confirmarEntrega(pedidoId, peticion);
        log.info("Pickup entregado: pedido={} tienda={} vendedor={} tercero={} origen={}",
                pedidoId, tiendaId, usuarioId, peticion.esTerceroAutorizado(), ventas.origen());
        return constancia;
    }

    private static boolean tieneDni(PedidoPickupResponse pedido) {
        return pedido.clienteDni() != null && !pedido.clienteDni().isBlank();
    }

    /**
     * Tienda del vendedor según su perfil local. Si el perfil todavía guarda la tienda como UUID
     * (pendiente D-05 del contrato), se usa el código textual por defecto que entiende Ventas.
     */
    private String tiendaDe(UUID usuarioId) {
        String tienda = personalTiendaRepository.findByUsuarioIdAndActivoTrue(usuarioId)
                .map(PersonalTienda::getTiendaId)
                .orElseThrow(() -> new AccesoDenegadoException("FORBIDDEN_USUARIO_SIN_TIENDA",
                        "El usuario autenticado no tiene un perfil activo en la tienda"));
        return tienda != null && tienda.startsWith("TIENDA-") ? tienda : tiendaPorDefecto;
    }
}
