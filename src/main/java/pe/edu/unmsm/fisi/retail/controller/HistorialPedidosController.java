package pe.edu.unmsm.fisi.retail.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.unmsm.fisi.retail.dto.DetallePedidoHistoricoResponse;
import pe.edu.unmsm.fisi.retail.dto.PaginaPedidosResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.service.HistorialPedidosService;

import java.util.UUID;

/**
 * Consulta y seguimiento histórico de pedidos (F6): RF-16 y RF-17.
 *
 * <p>Rutas canónicas del contrato ({@code api-contracts.md} §5.6). Ambas exigen token válido y perfil
 * activo de tienda {@code VENDEDOR} o {@code SUPERVISOR}:</p>
 * <ul>
 *   <li>{@code GET /api/v1/retail/pedidos?codigoPedido=&documentoCliente=&page=&size=} — búsqueda (RF-16)</li>
 *   <li>{@code GET /api/v1/retail/pedidos/{pedidoId}} — detalle con historial de estados (RF-17)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/retail/pedidos")
@RequiredArgsConstructor
public class HistorialPedidosController {

    private final HistorialPedidosService historialPedidosService;

    /** Pedidos por código o por DNI/RUC, del más reciente al más antiguo (RF-16). */
    @GetMapping
    public PaginaPedidosResponse buscar(@AuthenticationPrincipal Jwt token,
                                        @RequestParam(required = false) String codigoPedido,
                                        @RequestParam(required = false) String documentoCliente,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return historialPedidosService.buscar(usuarioDelToken(token), codigoPedido, documentoCliente, page, size);
    }

    /** Detalle del pedido con prendas e historial de estados (RF-17). */
    @GetMapping("/{pedidoId}")
    public DetallePedidoHistoricoResponse detalle(@AuthenticationPrincipal Jwt token, @PathVariable String pedidoId) {
        return historialPedidosService.detalle(usuarioDelToken(token), pedidoId);
    }

    /** Extrae el {@code sub} del token, igual que {@code PickupController}. */
    private UUID usuarioDelToken(Jwt token) {
        try {
            return UUID.fromString(token.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AccesoDenegadoException("TOKEN_SIN_USUARIO_VALIDO",
                    "El token no identifica a un usuario valido");
        }
    }
}
