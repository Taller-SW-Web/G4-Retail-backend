package pe.edu.unmsm.fisi.retail.integracion.ventas;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import pe.edu.unmsm.fisi.retail.client.dto.VentasCrearPedidoRequest;
import pe.edu.unmsm.fisi.retail.client.dto.VentasPedidoResponse;
import pe.edu.unmsm.fisi.retail.dto.SincronizacionOfflineRequest.VentaOffline;
import pe.edu.unmsm.fisi.retail.exception.ReglaNegocioException;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/** RF-28: exige confirmación real. No usa el fallback a pedidos en memoria de F5. */
@Slf4j
@Component
public class ContingenciaVentasHttp implements ContingenciaVentasPort {

    private final RestClient http;
    private final TokenServicioG7 token;

    public ContingenciaVentasHttp(
            @Value("${retail.integracion.ventas.url:https://g5-servicio-ventas.onrender.com}") String base,
            @Value("${retail.integracion.ventas.timeout-ms:3500}") int timeout,
            TokenServicioG7 token) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofMillis(timeout));
        fabrica.setReadTimeout(Duration.ofMillis(timeout));
        this.http = RestClient.builder().baseUrl(base).requestFactory(fabrica).build();
        this.token = token;
    }

    @Override
    public String registrar(VentaOffline v, String tienda, String clave, String comprobante) {
        // El contrato F10 no transporta datos de identidad/factura: no se inventan documentos.
        if (v.totalNeto().compareTo(new BigDecimal("700")) >= 0 || v.clienteId() != null) {
            throw new ReglaNegocioException("IDENTIFICACION_REQUIERE_REVISION", "Debe conciliar la identidad el supervisor");
        }

        VentasCrearPedidoRequest.Contacto contacto = new VentasCrearPedidoRequest.Contacto(
                null,
                "CLIENTE CONTINGENCIA",
                "DNI",
                "00000000",
                "999999999",
                "contingencia@retail.g4"
        );

        List<VentasCrearPedidoRequest.Item> items = v.items().stream()
                .map(it -> new VentasCrearPedidoRequest.Item(
                        "PROD-" + it.sku(),
                        it.sku(),
                        "Prenda offline SKU " + it.sku(),
                        it.cantidad(),
                        it.precioUnitario()
                ))
                .toList();

        VentasCrearPedidoRequest.Envio envio = new VentasCrearPedidoRequest.Envio(
                "TIENDA",
                BigDecimal.ZERO,
                "CLIENTE CONTINGENCIA",
                "Lima",
                "Lima",
                "Miraflores",
                "Tienda Mostrador",
                "CONTINGENCIA",
                tienda != null ? tienda : "TIENDA-MIRAFLORES"
        );

        OffsetDateTime fechaOffline;
        try {
            fechaOffline = OffsetDateTime.parse(v.fechaHoraOffline());
        } catch (Exception e) {
            fechaOffline = OffsetDateTime.now();
        }

        VentasCrearPedidoRequest.Pago pago = new VentasCrearPedidoRequest.Pago(
                "EFECTIVO",
                "PEN",
                v.totalNeto(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                v.totalNeto(),
                v.correlativoContingencia(),
                comprobante,
                v.totalNeto(),
                BigDecimal.ZERO,
                fechaOffline
        );

        VentasCrearPedidoRequest request = new VentasCrearPedidoRequest(
                "RETAIL",
                contacto,
                items,
                null,
                envio,
                pago
        );

        try {
            var reqBuilder = http.post()
                    .uri("/api/v1/pedidos")
                    .header("Idempotency-Key", clave)
                    .contentType(MediaType.APPLICATION_JSON);

            if (token != null && token.configurado()) {
                reqBuilder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token.obtener());
            }

            VentasPedidoResponse res = reqBuilder
                    .body(request)
                    .retrieve()
                    .body(VentasPedidoResponse.class);

            if (res == null || res.pedidoId() == null || res.pedidoId().isBlank()) {
                throw new ServicioExternoNoDisponibleException("VENTAS_NO_DISPONIBLE", "G5 respondió sin pedidoId");
            }
            return res.pedidoId();
        } catch (RestClientResponseException e) {
            log.error("Error respuesta G5 al sincronizar venta {}: status={}", v.ventaLocalUuid(), e.getStatusCode());
            throw new ServicioExternoNoDisponibleException("VENTAS_NO_DISPONIBLE", "G5 respondió con error HTTP " + e.getStatusCode());
        } catch (Exception e) {
            log.error("Fallo de comunicación con G5 al sincronizar venta {}: {}", v.ventaLocalUuid(), e.getMessage());
            throw new ServicioExternoNoDisponibleException("VENTAS_NO_DISPONIBLE", "No se pudo contactar con G5: " + e.getMessage());
        }
    }
}
