package pe.edu.unmsm.fisi.retail.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.unmsm.fisi.retail.dto.BoletaNumero;
import pe.edu.unmsm.fisi.retail.dto.RegistroCambioRequest;
import pe.edu.unmsm.fisi.retail.dto.ValeCompraResponse;
import pe.edu.unmsm.fisi.retail.dto.ValidarCambioResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.service.CambioService;

import java.util.UUID;

/**
 * Cambios de prenda y vales de compra en mostrador (F9).
 *
 * <ul>
 *   <li>{@code GET /api/v1/retail/cambios/validar/{boletaNumero}}: valida el comprobante y el plazo (RF-23).</li>
 *   <li>{@code POST /api/v1/retail/cambios}: registra la inspeccion (RF-24) y emite el vale (RF-25).</li>
 * </ul>
 *
 * <p>Rutas, plazo de 7 dias y emision del vale por Retail segun la reparticion de la Semana 11,
 * confirmadas por el PO.</p>
 */
@RestController
@RequestMapping("/api/v1/retail/cambios")
@RequiredArgsConstructor
public class CambioController {

    private final CambioService cambioService;

    /**
     * Valida un comprobante para un cambio.
     *
     * <p>Responde {@code 200} tambien si el plazo vencio, con {@code plazoValido = false}: la pantalla
     * necesita mostrar la compra con la etiqueta de plazo vencido.</p>
     *
     * <p>Errores: {@code 400 PARAMETRO_INVALIDO} si el numero no tiene el formato {@code B001-00000045};
     * {@code 404 COMPROBANTE_NO_ENCONTRADO}; {@code 422 PEDIDO_NO_APTO} si no esta pagado ni entregado.</p>
     */
    @GetMapping("/validar/{boletaNumero}")
    public ValidarCambioResponse validar(
            @AuthenticationPrincipal Jwt token,
            @PathVariable @Pattern(regexp = BoletaNumero.PATRON, message = BoletaNumero.MENSAJE) String boletaNumero) {
        return cambioService.validar(usuarioDelToken(token), boletaNumero);
    }

    /**
     * Registra el cambio y emite el vale.
     *
     * <p>El {@code Idempotency-Key} es obligatorio: si el vendedor pulsa dos veces o la red reintenta,
     * se devuelve el mismo vale en lugar de emitir otro por la misma prenda.</p>
     *
     * <p>Errores: {@code 400} por datos o cabecera faltantes; {@code 404 COMPROBANTE_NO_ENCONTRADO};
     * {@code 409 IDEMPOTENCY_CONFLICT}; {@code 422} por {@code CAJA_NO_ABIERTA}, {@code PLAZO_VENCIDO},
     * {@code PRENDA_NO_INCLUIDA}, {@code CANTIDAD_NO_DISPONIBLE} o {@code INSPECCION_NO_APTA}.</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ValeCompraResponse registrar(
            @AuthenticationPrincipal Jwt token,
            @RequestHeader("Idempotency-Key")
            @NotBlank(message = "La cabecera Idempotency-Key no puede estar vacia")
            @Size(max = 64, message = "La cabecera Idempotency-Key admite hasta 64 caracteres")
            String idempotencyKey,
            @Valid @RequestBody RegistroCambioRequest peticion) {
        return cambioService.registrar(usuarioDelToken(token), idempotencyKey, peticion);
    }

    private UUID usuarioDelToken(Jwt token) {
        try {
            return UUID.fromString(token.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AccesoDenegadoException("TOKEN_SIN_USUARIO_VALIDO",
                    "El token no identifica a un usuario valido");
        }
    }
}
