package pe.edu.unmsm.fisi.retail.config.gestionapi;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Límite de peticiones por cliente en ventanas fijas de un minuto (contrato v0.5, §2.8).
 *
 * <p>El cliente es el vendedor (el {@code sub} del JWT) o, si la petición no trae token, su IP. Cada
 * minuto de reloj abre una ventana nueva con el contador en cero.</p>
 */
public class LimitadorPeticiones {

    private static final long MILIS_VENTANA = 60_000L;

    private final int limitePorMinuto;
    private final Clock reloj;
    private final Map<String, Ventana> ventanas = new ConcurrentHashMap<>();

    public LimitadorPeticiones(int limitePorMinuto, Clock reloj) {
        if (limitePorMinuto < 1) {
            throw new IllegalArgumentException("El límite de peticiones por minuto debe ser al menos 1");
        }
        this.limitePorMinuto = limitePorMinuto;
        this.reloj = reloj;
    }

    /** Resultado de consumir una petición. {@code reintentarEnSegundos} solo importa si no se permitió. */
    public record Resultado(boolean permitido, int limite, int restantes, long reintentarEnSegundos) {
    }

    /** Cuenta una petición del cliente y dice si entra en el límite de su ventana actual. */
    public Resultado consumir(String cliente) {
        long ahora = reloj.millis();
        long ventanaActual = ahora / MILIS_VENTANA;
        Ventana ventana = ventanas.compute(cliente, (clave, previa) ->
                previa == null || previa.numero() != ventanaActual ? new Ventana(ventanaActual, new AtomicInteger()) : previa);
        int usadas = ventana.contador().incrementAndGet();
        long finVentana = (ventanaActual + 1) * MILIS_VENTANA;
        long segundos = Math.max(1, (finVentana - ahora + 999) / 1000);
        boolean permitido = usadas <= limitePorMinuto;
        return new Resultado(permitido, limitePorMinuto, Math.max(0, limitePorMinuto - usadas), segundos);
    }

    /** Borra las ventanas de minutos ya pasados para que el mapa no crezca sin límite. */
    public void limpiarVencidas() {
        long ventanaActual = reloj.millis() / MILIS_VENTANA;
        ventanas.entrySet().removeIf(e -> e.getValue().numero() < ventanaActual);
    }

    int clientesActivos() {
        return ventanas.size();
    }

    private record Ventana(long numero, AtomicInteger contador) {
    }
}
