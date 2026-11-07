package pe.edu.unmsm.fisi.retail.service;

import org.springframework.stereotype.Component;
import pe.edu.unmsm.fisi.retail.dto.InspeccionPrenda;
import pe.edu.unmsm.fisi.retail.model.DestinoPrenda;
import pe.edu.unmsm.fisi.retail.model.EstadoAprobacionCambio;
import pe.edu.unmsm.fisi.retail.model.MotivoCambio;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reglas de inspeccion fisica de una prenda que el cliente quiere cambiar (F9, RF-24).
 *
 * <p>Protege a la tienda del problema que motiva la spec: recibir prendas usadas o sin etiquetas que
 * despues no se pueden vender.</p>
 *
 * <ul>
 *   <li><strong>Cambio ordinario</strong> (talla o modelo): exige etiquetas intactas y prenda sin uso.
 *       Si cumple, se sugiere reingresarla al inventario.</li>
 *   <li><strong>Falla de fabrica</strong>: se aprueba por garantia aunque falten las etiquetas, y se
 *       sugiere merma, porque una prenda defectuosa no vuelve a la venta.</li>
 *   <li>El <strong>empaque original</strong> se registra pero no bloquea: ni el RF-24 ni el contrato lo
 *       cuentan entre las condiciones criticas.</li>
 * </ul>
 */
@Component
public class PoliticaInspeccionPrenda {

    static final String SIN_ETIQUETAS = "Las etiquetas originales no estan intactas";
    static final String CON_USO = "La prenda presenta signos de uso";
    static final String SIN_EMPAQUE = "No trae el empaque original";
    static final String POR_GARANTIA = "Aprobado por garantia: falla de fabrica";

    /**
     * Evalua la inspeccion segun el motivo del cambio.
     *
     * @throws NullPointerException si falta el motivo o la inspeccion: no hay forma razonable de
     *                              decidir sin ellos, y la validacion de la peticion debe impedirlo antes
     */
    public Resultado evaluar(MotivoCambio motivo, InspeccionPrenda inspeccion) {
        Objects.requireNonNull(motivo, "motivo");
        Objects.requireNonNull(inspeccion, "inspeccion");

        List<String> observaciones = new ArrayList<>();
        if (!inspeccion.empaqueOriginal()) {
            observaciones.add(SIN_EMPAQUE);
        }

        if (!motivo.esOrdinario()) {
            observaciones.add(0, POR_GARANTIA);
            return new Resultado(EstadoAprobacionCambio.APROBADO_EN_TIENDA, DestinoPrenda.MERMA_GARANTIA,
                    List.copyOf(observaciones));
        }

        List<String> incumplidas = new ArrayList<>();
        if (!inspeccion.etiquetasIntactas()) {
            incumplidas.add(SIN_ETIQUETAS);
        }
        if (!inspeccion.sinSignosUso()) {
            incumplidas.add(CON_USO);
        }

        if (!incumplidas.isEmpty()) {
            incumplidas.addAll(observaciones);
            return new Resultado(EstadoAprobacionCambio.RECHAZADO, null, List.copyOf(incumplidas));
        }
        return new Resultado(EstadoAprobacionCambio.APROBADO_EN_TIENDA, DestinoPrenda.REINGRESO_INVENTARIO,
                List.copyOf(observaciones));
    }

    /**
     * Veredicto de la inspeccion.
     *
     * @param estado        aprobado en tienda o rechazado
     * @param destino       destino sugerido para la prenda; {@code null} si se rechazo, porque la prenda
     *                      no se recibe
     * @param observaciones motivos del rechazo, o notas que conviene dejar registradas aunque se apruebe
     */
    public record Resultado(EstadoAprobacionCambio estado, DestinoPrenda destino, List<String> observaciones) {

        public boolean aprobado() {
            return estado == EstadoAprobacionCambio.APROBADO_EN_TIENDA;
        }
    }
}
