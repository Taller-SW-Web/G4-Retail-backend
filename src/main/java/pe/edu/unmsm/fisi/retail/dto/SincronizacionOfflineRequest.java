package pe.edu.unmsm.fisi.retail.dto;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Contrato §5.10: cada fila se valida por separado para no abortar el lote. */
public record SincronizacionOfflineRequest(
    @NotBlank @Size(max=20) String terminalPos,
    @NotNull UUID turnoId,
    @NotEmpty @Size(max=50) List<VentaOffline> lote) {
    public record VentaOffline(
        @NotNull UUID ventaLocalUuid,
        @NotBlank @Size(max=100) String correlativoContingencia,
        @NotBlank String fechaHoraOffline,
        @NotEmpty @Size(max=200) @Valid List<Item> items,
        @NotNull @Positive @Digits(integer=10,fraction=2) BigDecimal totalNeto,
        @NotNull @Positive @Digits(integer=10,fraction=2) BigDecimal efectivoRecibido,
        @NotNull @PositiveOrZero @Digits(integer=10,fraction=2) BigDecimal vuelto,
        String clienteId,
        @NotBlank @Pattern(regexp="[a-f0-9]{64}") String hashIntegridad) {}
    public record Item(@NotBlank @Size(max=100) String sku, @Positive int cantidad,
        @NotNull @Positive @Digits(integer=10,fraction=2) BigDecimal precioUnitario) {}
}
