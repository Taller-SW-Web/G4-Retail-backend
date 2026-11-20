package pe.edu.unmsm.fisi.retail.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Traduce la tienda del perfil local al código de sucursal ({@code TIENDA-...}) que entiende Ventas (G5).
 *
 * <p>Ventas exige {@code envio.tiendaId} como código de sucursal (acuerdo R5, decisión D-05). Algunos
 * perfiles todavía guardan la tienda como UUID; la tabla {@code retail.tienda.codigos-por-uuid}
 * (pares {@code uuid=TIENDA-X} separados por coma) da su código. Un UUID sin código no se adivina.</p>
 */
@Slf4j
@Component
public class CodigosSucursal {

    private static final String PREFIJO_SUCURSAL = "TIENDA-";

    private final Map<String, String> codigosPorUuid;

    public CodigosSucursal(@Value("${retail.tienda.codigos-por-uuid:}") String codigosPorUuid) {
        this.codigosPorUuid = leer(codigosPorUuid);
    }

    /**
     * @return el código {@code TIENDA-...} de la tienda; la misma entrada si ya es un código o si el
     *         UUID no tiene código configurado (se registra un aviso)
     */
    public String codigoDe(String tienda) {
        if (tienda == null || tienda.startsWith(PREFIJO_SUCURSAL)) {
            return tienda;
        }
        String codigo = codigosPorUuid.get(tienda.toLowerCase());
        if (codigo == null) {
            log.warn("La tienda {} no tiene código de sucursal configurado; se envía sin traducir", tienda);
            return tienda;
        }
        return codigo;
    }

    private static Map<String, String> leer(String texto) {
        if (texto == null || texto.isBlank()) {
            return Map.of();
        }
        return Arrays.stream(texto.split(","))
                .map(String::trim)
                .filter(par -> par.contains("="))
                .map(par -> par.split("=", 2))
                .collect(Collectors.toUnmodifiableMap(
                        par -> par[0].trim().toLowerCase(),
                        par -> par[1].trim(),
                        (primero, segundo) -> primero));
    }
}
