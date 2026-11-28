package pe.edu.unmsm.fisi.retail.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pe.edu.unmsm.fisi.retail.model.TipoDestinoActa;

import java.util.List;
import java.util.UUID;

/**
 * Cuerpo de {@code POST /api/v1/retail/inventario/actas-merma} (F12, RF-34, contrato 5.12).
 *
 * <p>La lista vacia no se rechaza aqui sino en el servicio, para responder el codigo del contrato
 * ({@code 400 LISTA_VACIA}) y no uno generico de validacion.</p>
 */
public record EmisionActaRequest(
        List<UUID> incidenciasIds,

        @NotNull(message = "El tipoDestino es obligatorio")
        TipoDestinoActa tipoDestino,

        @Size(max = 500, message = "Las observaciones admiten hasta 500 caracteres")
        String observaciones) {
}
