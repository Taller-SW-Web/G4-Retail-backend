/**
 * Objetos de transferencia ({@code Request} y {@code Response}), independientes de las entidades JPA.
 *
 * <p>Mantenerlos separados evita exponer columnas internas y permite que la base de datos cambie
 * sin romper el contrato de API. Los campos viajan en {@code camelCase} y las enumeraciones en
 * {@code UPPER_SNAKE_CASE} (seccion 2.1 del contrato v0.4).</p>
 */
package pe.edu.unmsm.fisi.retail.dto;
