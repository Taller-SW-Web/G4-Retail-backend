package pe.edu.unmsm.fisi.retail.integracion.boletas;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Doble de las boletas emitidas, mientras las ventas no se persistan en {@code RET_VENTA} (F5).
 *
 * <p>Se activa con el perfil {@code mock}. Trae dos boletas de prueba, con fechas relativas al momento
 * de arrancar, para que siempre haya un caso dentro del plazo y otro vencido:</p>
 *
 * <ul>
 *   <li>{@code B001-00000045}: emitida hace 2 dias, dentro del plazo.</li>
 *   <li>{@code B001-00000012}: emitida hace 20 dias, fuera del plazo.</li>
 * </ul>
 *
 * <p>Los SKU coinciden con los del mock de Productos (G6), para que la demo sea coherente.</p>
 */
@Component
@Profile("local & mock")
public class BoletasEmitidasMock implements BoletasEmitidasPort {

    private final Map<String, BoletaEmitida> boletas;

    public BoletasEmitidasMock() {
        this(Clock.systemUTC());
    }

    BoletasEmitidasMock(Clock reloj) {
        Instant ahora = reloj.instant();
        this.boletas = List.of(
                new BoletaEmitida("B001-00000045", "PED-2026-00412", ahora.minus(Duration.ofDays(2)), "PAGADO", List.of(
                        new BoletaEmitida.Item("ZAPVEL-38-NEG", "Zapatillas Velocity X, talla 38, negro",
                                new BigDecimal("249.00"), 1),
                        new BoletaEmitida.Item("POLTEC-M-BLA", "Polo tecnico DryFit, talla M, blanco",
                                new BigDecimal("59.90"), 2))),
                new BoletaEmitida("B001-00000012", "PED-2026-00398", ahora.minus(Duration.ofDays(20)), "ENTREGADO", List.of(
                        new BoletaEmitida.Item("ZAPTRA-40-VER", "Zapatillas Trail Andes, talla 40, verde",
                                new BigDecimal("279.00"), 1)))
        ).stream().collect(Collectors.toUnmodifiableMap(BoletaEmitida::numero, Function.identity()));
    }

    @Override
    public Optional<BoletaEmitida> buscarPorNumero(String numero, String tienda, boolean bloquear) {
        return Optional.ofNullable(numero).map(boletas::get);
    }
}
