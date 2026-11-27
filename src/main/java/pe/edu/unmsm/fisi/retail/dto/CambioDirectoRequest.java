package pe.edu.unmsm.fisi.retail.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import pe.edu.unmsm.fisi.retail.model.MotivoCambio;

import java.util.List;
import java.util.UUID;

/**
 * Cuerpo de {@code POST /api/v1/retail/cambios}: cambio directo de prenda (F9, contrato 5.9).
 *
 * @param boletaNumero   boleta de la compra original, por ejemplo {@code B001-00000045}
 * @param terminalPos    terminal del turno de caja abierto
 * @param turnoId        turno de caja {@code ABIERTA}
 * @param motivo         {@code CAMBIO_TALLA} o {@code CAMBIO_MODELO}
 * @param devuelto       prenda que el cliente devuelve, con su inspeccion
 * @param nuevos         prendas que se lleva
 * @param pagoDiferencia obligatorio solo en mayor valor, con las reglas del pago de F5
 * @param comprobante    obligatorio solo en mayor valor, con las reglas de F5
 * @param clienteId      opcional
 */
public record CambioDirectoRequest(
        @NotBlank(message = "La boleta es obligatoria")
        @Pattern(regexp = BoletaNumero.PATRON, message = BoletaNumero.MENSAJE)
        String boletaNumero,

        @NotBlank(message = "El terminalPos es obligatorio")
        String terminalPos,

        @NotBlank(message = "El turnoId es obligatorio")
        String turnoId,

        @NotNull(message = "El motivo es obligatorio")
        MotivoCambio motivo,

        @NotNull(message = "La prenda devuelta es obligatoria")
        @Valid
        Devuelto devuelto,

        @NotEmpty(message = "Indica al menos una prenda nueva")
        @Valid
        List<PrendaNueva> nuevos,

        @Valid
        VentaRequest.PagoDto pagoDiferencia,

        @Valid
        VentaRequest.ComprobanteDto comprobante,

        UUID clienteId) {

    public CambioDirectoRequest {
        nuevos = nuevos == null ? null : List.copyOf(nuevos);
    }

    public record Devuelto(
            @NotBlank(message = "El SKU devuelto es obligatorio")
            String sku,

            @Min(value = 1, message = "La cantidad minima es 1")
            @Max(value = 99, message = "La cantidad maxima es 99")
            int cantidad,

            @NotNull(message = "La inspeccion es obligatoria")
            @Valid
            InspeccionPrenda inspeccion) {}

    public record PrendaNueva(
            @NotBlank(message = "El SKU de la prenda nueva es obligatorio")
            String sku,

            @Min(value = 1, message = "La cantidad minima es 1")
            @Max(value = 99, message = "La cantidad maxima es 99")
            int cantidad) {}
}
