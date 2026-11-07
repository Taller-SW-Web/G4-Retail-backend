package pe.edu.unmsm.fisi.retail.dto;

import java.util.List;

/** Página de pedidos (contrato §2.7): {@code page} empieza en 0. */
public record PaginaPedidosResponse(List<PedidoResumenDto> items, int page, int size, long total) {

    public PaginaPedidosResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
