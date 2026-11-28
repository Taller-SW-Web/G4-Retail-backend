package pe.edu.unmsm.fisi.retail.integracion.productos;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import pe.edu.unmsm.fisi.retail.exception.ServicioExternoNoDisponibleException;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.DisponibilidadComercial;
import pe.edu.unmsm.fisi.retail.integracion.productos.dto.VarianteComercial;
import pe.edu.unmsm.fisi.retail.integracion.seguridad.TokenServicioG7;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contrato §5.2 (rendimiento del catálogo con G6 real): las variantes se piden en paralelo con un tope
 * de llamadas simultáneas, y la caché vencida se sirve sin esperar mientras se refresca en segundo plano.
 * Usa un servidor HTTP real que demora cada respuesta, como lo hace G6.
 */
@DisplayName("ProductosHttp — rendimiento del catálogo contra un G6 lento")
class ProductosHttpRendimientoTest {

    private static final long DEMORA_MS = 300;

    private HttpServer servidor;
    private final AtomicInteger llamadas = new AtomicInteger();
    private final AtomicInteger llamadasPrecios = new AtomicInteger();
    private final AtomicInteger simultaneas = new AtomicInteger();
    private final AtomicInteger maximoSimultaneas = new AtomicInteger();
    private volatile int estadoDetalle = 200;
    private String base;

    @BeforeEach
    void levantar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.setExecutor(Executors.newCachedThreadPool());
        servidor.createContext("/api/v1/productos/", intercambio -> {
            int ahora = simultaneas.incrementAndGet();
            maximoSimultaneas.accumulateAndGet(ahora, Math::max);
            llamadas.incrementAndGet();
            try {
                Thread.sleep(DEMORA_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String id = intercambio.getRequestURI().getPath().substring("/api/v1/productos/".length());
            byte[] cuerpo = ("{\"variants\":[{\"sku\":\"SKU-" + id + "\"}]}").getBytes(StandardCharsets.UTF_8);
            simultaneas.decrementAndGet();
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(estadoDetalle, cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        servidor.createContext("/api/v1/inventario/disponibilidad/comercial", intercambio -> {
            llamadas.incrementAndGet();
            String skus = intercambio.getRequestURI().getQuery().replaceAll(".*skus=([^&]*).*", "$1");
            String items = String.join(",", java.util.Arrays.stream(skus.split(","))
                    .map(sku -> "{\"sku\":\"" + sku + "\",\"status\":\"DISPONIBLE\"}").toList());
            byte[] cuerpo = ("{\"items\":[" + items + "]}").getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(200, cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        servidor.createContext("/api/v1/precios", intercambio -> {
            llamadas.incrementAndGet();
            llamadasPrecios.incrementAndGet();
            String skus = intercambio.getRequestURI().getQuery().replaceAll(".*skus=([^&]*).*", "$1");
            // SKU-SIN-PRECIO no tiene precio vigente en G6: no aparece en la respuesta.
            String items = String.join(",", java.util.Arrays.stream(skus.split(","))
                    .filter(sku -> !sku.equals("SKU-SIN-PRECIO"))
                    .map(sku -> "{\"sku\":\"" + sku + "\",\"precio_regular\":59.9,\"precio_oferta\":null}").toList());
            byte[] cuerpo = ("{\"items\":[" + items + "]}").getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(200, cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        servidor.start();
        base = "http://127.0.0.1:" + servidor.getAddress().getPort() + "/api/v1";
    }

    @AfterEach
    void detener() {
        servidor.stop(0);
    }

    private ProductosHttp adaptador(Clock reloj, int paralelismo) {
        TokenServicioG7 token = mock(TokenServicioG7.class);
        when(token.obtener()).thenReturn("token-productos");
        return new ProductosHttp(RestClient.builder().baseUrl(base).build(), token, reloj,
                Duration.ofSeconds(60), Duration.ofMinutes(15), paralelismo);
    }

    private static List<String> ids(int cuantos) {
        return IntStream.rangeClosed(1, cuantos).mapToObj(i -> "PROD-" + i).toList();
    }

    @Test
    @DisplayName("Pide las variantes de la página en paralelo y respeta el tope de llamadas simultáneas")
    void variantesEnParalelo() {
        ProductosHttp productos = adaptador(Clock.systemUTC(), 8);

        long inicio = System.nanoTime();
        Map<String, List<VarianteComercial>> variantes = productos.variantesDe(ids(16));
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        // Una tras otra serían 16 × 300 ms = 4,8 s; con 8 a la vez son 2 tandas, unos 600 ms.
        assertThat(ms).isLessThan(16 * DEMORA_MS / 2);
        assertThat(maximoSimultaneas.get()).isLessThanOrEqualTo(8).isGreaterThan(1);
        assertThat(variantes).hasSize(16);
        assertThat(variantes.keySet()).containsExactlyElementsOf(ids(16));
        assertThat(variantes.get("PROD-3")).extracting(VarianteComercial::sku).containsExactly("SKU-PROD-3");
    }

    @Test
    @DisplayName("La segunda vez responde desde caché, sin volver a llamar a G6")
    void segundaVezDesdeCache() {
        ProductosHttp productos = adaptador(Clock.systemUTC(), 8);
        productos.variantesDe(ids(5));
        int tras = llamadas.get();

        long inicio = System.nanoTime();
        productos.variantesDe(ids(5));

        assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(DEMORA_MS);
        assertThat(llamadas.get()).isEqualTo(tras);
    }

    @Test
    @DisplayName("Con la caché vencida devuelve el dato anterior al instante y lo refresca en segundo plano")
    void cacheVencidaNoHaceEsperar() {
        RelojAjustable reloj = new RelojAjustable(Instant.parse("2026-11-27T10:00:00Z"));
        ProductosHttp productos = adaptador(reloj, 8);
        productos.listarVariantes("PROD-1");
        assertThat(llamadas.get()).isEqualTo(1);

        reloj.avanzar(Duration.ofMinutes(16));
        long inicio = System.nanoTime();
        List<VarianteComercial> variantes = productos.listarVariantes("PROD-1");

        assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(DEMORA_MS);
        assertThat(variantes).extracting(VarianteComercial::sku).containsExactly("SKU-PROD-1");
        await().atMost(Duration.ofSeconds(3)).until(() -> llamadas.get() == 2);
    }

    @Test
    @DisplayName("Un dato vencido hace más de 30 minutos ya no se muestra: espera a G6")
    void cacheDemasiadoViejaEspera() {
        RelojAjustable reloj = new RelojAjustable(Instant.parse("2026-11-27T10:00:00Z"));
        ProductosHttp productos = adaptador(reloj, 8);
        productos.listarVariantes("PROD-1");

        reloj.avanzar(Duration.ofMinutes(15).plus(ProductosHttp.ANTIGUEDAD_MAXIMA).plusSeconds(1));
        long inicio = System.nanoTime();
        productos.listarVariantes("PROD-1");

        assertThat((System.nanoTime() - inicio) / 1_000_000).isGreaterThanOrEqualTo(DEMORA_MS - 50);
        assertThat(llamadas.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Si G6 falla en una de las llamadas en paralelo, responde 503 PRODUCTOS_NO_DISPONIBLE")
    void fallaEnParalelo() {
        ProductosHttp productos = adaptador(Clock.systemUTC(), 8);
        estadoDetalle = 500;

        assertThatThrownBy(() -> productos.variantesDe(ids(4)))
                .isInstanceOf(ServicioExternoNoDisponibleException.class)
                .hasMessageContaining("Productos");
    }

    @Test
    @DisplayName("Parte la disponibilidad en tramos de 100 SKU y los pide en paralelo")
    void disponibilidadEnTramos() {
        ProductosHttp productos = adaptador(Clock.systemUTC(), 8);
        List<String> skus = IntStream.rangeClosed(1, 250).mapToObj(i -> "SKU-" + i).toList();

        List<DisponibilidadComercial> estados = productos.consultarDisponibilidad(skus);

        assertThat(estados).hasSize(250);
        assertThat(estados).extracting(DisponibilidadComercial::sku).containsExactlyElementsOf(skus);
        assertThat(llamadas.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("Pide los precios en lote: 250 SKU son 3 llamadas a GET /precios, no 250")
    void preciosEnLote() {
        ProductosHttp productos = adaptador(Clock.systemUTC(), 4);
        List<String> skus = IntStream.rangeClosed(1, 250).mapToObj(i -> "SKU-" + i).toList();

        Map<String, pe.edu.unmsm.fisi.retail.integracion.productos.dto.Precio> precios = productos.preciosDe(skus);

        assertThat(llamadasPrecios.get()).isEqualTo(3);
        assertThat(precios).hasSize(250);
        assertThat(precios.get("SKU-7").precioEfectivo()).isEqualByComparingTo("59.9");
    }

    @Test
    @DisplayName("El SKU sin precio no aparece, y la segunda vez los precios salen de caché (también para consultarPrecio)")
    void preciosEnCache() {
        ProductosHttp productos = adaptador(Clock.systemUTC(), 4);

        Map<String, pe.edu.unmsm.fisi.retail.integracion.productos.dto.Precio> precios =
                productos.preciosDe(List.of("SKU-1", "SKU-SIN-PRECIO"));
        assertThat(precios).containsOnlyKeys("SKU-1");

        productos.preciosDe(List.of("SKU-1", "SKU-SIN-PRECIO"));
        assertThat(productos.consultarPrecio("SKU-1")).isPresent();
        assertThat(productos.consultarPrecio("SKU-SIN-PRECIO")).isEmpty();
        assertThat(llamadasPrecios.get()).isEqualTo(1);
        assertThat(llamadas.get()).isEqualTo(1);
    }

    /** Reloj que el test adelanta a mano para vencer la caché sin esperar. */
    private static final class RelojAjustable extends Clock {
        private volatile Instant ahora;

        RelojAjustable(Instant inicio) {
            this.ahora = inicio;
        }

        void avanzar(Duration cuanto) {
            ahora = ahora.plus(cuanto);
        }

        @Override
        public Instant instant() {
            return ahora;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zona) {
            return this;
        }
    }
}
