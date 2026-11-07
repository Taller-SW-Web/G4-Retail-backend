package pe.edu.unmsm.fisi.retail.dto;

/**
 * Checklist de inspeccion fisica de la prenda que trae el cliente (F9, RF-24).
 *
 * <p>Es el objeto {@code inspeccion} del cuerpo de {@code POST /api/v1/retail/cambios} (contrato,
 * seccion 5.9). Los campos son primitivos a proposito: una casilla que no llega cuenta como
 * <strong>no cumplida</strong>, que es el lado seguro cuando lo que esta en juego es recibir una
 * prenda que despues no se puede vender.</p>
 *
 * @param etiquetasIntactas las etiquetas colgantes originales siguen puestas
 * @param sinSignosUso      la prenda esta limpia y sin signos de uso
 * @param empaqueOriginal   viene en su caja o empaque original
 */
public record InspeccionPrenda(boolean etiquetasIntactas, boolean sinSignosUso, boolean empaqueOriginal) {
}
