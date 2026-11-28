package pe.edu.unmsm.fisi.retail.integracion.productos.mock;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import pe.edu.unmsm.fisi.retail.integracion.productos.CampanasPort;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.CampanaComercial;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Doble de las campañas de G6 (contrato v0.5, §6), activo con el perfil {@code mock}. Sin ese
 * perfil se usa {@link pe.edu.unmsm.fisi.retail.integracion.productos.CampanasHttp}.
 *
 * <p>Los cupones coinciden con los que acepta el cálculo del carrito (RF-11), para que el clic en la
 * campaña funcione de punta a punta. Incluye a propósito una campaña vencida y una de otro canal, que
 * el servicio debe descartar.</p>
 */
@Component
@Profile("mock")
public class CampanasMock implements CampanasPort {

    private static final OffsetDateTime INICIO_NOV = OffsetDateTime.parse("2026-11-01T05:00:00Z");
    private static final OffsetDateTime FIN_DIC = OffsetDateTime.parse("2027-01-01T04:59:59Z");

    private static final List<CampanaComercial> CAMPANAS = List.of(
            new CampanaComercial("CMP-2026-011", "10% de verano con cupón", "Descuento de temporada en mostrador",
                    Set.of("RETAIL", "MARKETPLACE"), "PORCENTAJE", new BigDecimal("10.00"), "VERANO2026",
                    null, "Un cupón por compra", INICIO_NOV, FIN_DIC),
            new CampanaComercial("CMP-2026-012", "10% en tu compra desde S/ 150", "Cupón de mostrador",
                    Set.of("RETAIL"), "PORCENTAJE", new BigDecimal("10.00"), "DESCUENTO10",
                    new BigDecimal("150.00"), "No acumulable con otras promociones", INICIO_NOV, FIN_DIC),
            new CampanaComercial("CMP-2026-013", "2x1 en medias deportivas", "Lleva dos pares y paga uno",
                    Set.of("RETAIL"), "NXM", null, null, null, "Se descuenta el par de menor precio",
                    INICIO_NOV, FIN_DIC),
            new CampanaComercial("CMP-2026-007", "Fiestas Patrias", "Campaña vencida",
                    Set.of("RETAIL"), "PORCENTAJE", new BigDecimal("20.00"), "PATRIAS2026", null, null,
                    OffsetDateTime.parse("2026-07-20T05:00:00Z"), OffsetDateTime.parse("2026-07-31T04:59:59Z")),
            new CampanaComercial("CMP-2026-014", "Envío gratis web", "Solo tienda virtual",
                    Set.of("MARKETPLACE"), "MONTO_FIJO", new BigDecimal("15.00"), "ENVIOWEB", null, null,
                    INICIO_NOV, FIN_DIC));

    @Override
    public List<CampanaComercial> listarCampanas() {
        return CAMPANAS;
    }

    @Override
    public String origen() {
        return "EN_MEMORIA";
    }
}
