package pe.edu.unmsm.fisi.retail.service;

import pe.edu.unmsm.fisi.retail.config.gestionapi.CorrelacionSaliente;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.dto.ClienteAltaRapidaRequest;
import pe.edu.unmsm.fisi.retail.dto.ClienteResponse;
import pe.edu.unmsm.fisi.retail.exception.AccesoDenegadoException;
import pe.edu.unmsm.fisi.retail.exception.DatoInvalidoException;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.ClientesG7Http;
import pe.edu.unmsm.fisi.retail.service.ValidadorDocumento.DocumentoValidado;
import pe.edu.unmsm.fisi.retail.service.ValidadorDocumento.TipoDocumento;

import java.time.Duration;

/**
 * Clientes de mostrador contra Seguridad (G7), dueño de los clientes (F3, contrato v0.5 §4.1).
 *
 * <p>Se activa con {@code retail.integracion.clientes.modo=g7}. Valida el documento en Retail (RF-09)
 * antes de llamar a G7 y reenvía el token del vendedor, porque G7 exige los permisos
 * {@code cliente.buscar} y {@code cliente.registrar} del usuario.</p>
 *
 * <ul>
 *   <li>La búsqueda devuelve el documento <strong>enmascarado</strong>, como lo entrega G7.</li>
 *   <li>El alta exige correo (G7 envía ahí el enlace de activación) y no admite RUC: los datos de una
 *   empresa para facturar se capturan en la venta (§5.5).</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "retail.integracion.clientes.modo", havingValue = "g7")
public class ClienteServiceG7 implements ClienteService {

    private final ClientesG7Http g7;

    public ClienteServiceG7(
            @Value("${retail.integracion.g7.usuarios-url:https://g7-auth-service.onrender.com}") String url,
            @Value("${retail.integracion.g7.timeout-ms:20000}") int timeoutMs) {
        this(new ClientesG7Http(RestClient.builder().baseUrl(url).requestFactory(fabrica(timeoutMs))
                .requestInterceptor(CorrelacionSaliente.INSTANCIA).build()));
        log.info("Clientes (F3) conectados a Seguridad (G7): url={}", url);
    }

    /** Para pruebas: permite inyectar el adaptador. */
    public ClienteServiceG7(ClientesG7Http g7) {
        this.g7 = g7;
    }

    @Override
    public ClienteResponse buscarPorDocumento(String tipoDocumento, String numeroDocumento) {
        DocumentoValidado documento = ValidadorDocumento.validarParaBusqueda(tipoDocumento, numeroDocumento);
        ClientesG7Http.ClienteEncontrado encontrado = g7.buscar(tokenVendedor(), documento.tipo().name(), documento.numero());
        return new ClienteResponse(encontrado.id(), documento.tipo().name(), encontrado.documentoEnmascarado(),
                encontrado.nombreCompleto());
    }

    @Override
    public ClienteResponse registrarAltaRapida(ClienteAltaRapidaRequest request) {
        DocumentoValidado documento = ValidadorDocumento.validar(request.tipoDocumento(), request.numeroDocumento());
        if (documento.tipo() == TipoDocumento.RUC) {
            throw new DatoInvalidoException("tipoDocumento",
                    "El RUC no se registra como cliente en Seguridad: capture la razón social en la venta");
        }
        if (request.email() == null || request.email().isBlank()) {
            throw new DatoInvalidoException("email",
                    "El correo es obligatorio para registrar al cliente en Seguridad (recibe el enlace de activación)");
        }
        ClientesG7Http.CuentaCreada cuenta = g7.registrar(tokenVendedor(), new ClientesG7Http.RegistroAsistido(
                request.email().trim(), request.nombres().trim(), request.apellidos().trim(),
                celular(request.telefono()), documento.tipo().name(), documento.numero()));
        log.info("Cliente registrado en Seguridad (G7): id={} estado={}", cuenta.id(), cuenta.estado());
        return new ClienteResponse(cuenta.id(), documento.tipo().name(), documento.numero(),
                (request.nombres().trim() + " " + request.apellidos().trim()).trim());
    }

    /** Celular en el formato de G7 ({@code +51} y 9 dígitos); si no se puede llevar a ese formato, no se envía. */
    static String celular(String telefono) {
        if (telefono == null) {
            return null;
        }
        String digitos = telefono.replaceAll("[^0-9]", "");
        if (digitos.length() == 11 && digitos.startsWith("51")) {
            digitos = digitos.substring(2);
        }
        return digitos.length() == 9 && digitos.startsWith("9") ? "+51" + digitos : null;
    }

    private static String tokenVendedor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getTokenValue();
        }
        throw new AccesoDenegadoException("TOKEN_SIN_USUARIO_VALIDO", "La operación exige el token del vendedor");
    }

    private static SimpleClientHttpRequestFactory fabrica(int timeoutMs) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofMillis(timeoutMs));
        fabrica.setReadTimeout(Duration.ofMillis(timeoutMs));
        return fabrica;
    }
}
