package cl.minimarket.pos.shared.web;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;

/**
 * Parámetros de paginación de la query (A.5): {@code page} desde 0 y {@code size} por defecto
 * {@value #DEFAULT_SIZE}, máximo {@value #MAX_SIZE}.
 *
 * <p>Decisión: un tamaño fuera de rango se <b>rechaza</b> (400 {@code VALIDATION_ERROR}) en lugar
 * de recortarse, para que el cliente no reciba silenciosamente menos filas de las pedidas.
 *
 * <p>Uso típico en un controlador:
 * <pre>
 * PageQuery pq = PageQuery.of(page, size);
 * Pageable pageable = pq.toPageable(sortParser.parse(sort));
 * </pre>
 *
 * @param page número de página, desde 0
 * @param size tamaño de página, entre 1 y {@value #MAX_SIZE}
 */
public record PageQuery(int page, int size) {

    /** Tamaño de página por defecto. */
    public static final int DEFAULT_SIZE = 20;

    /** Tamaño de página máximo. */
    public static final int MAX_SIZE = 200;

    /**
     * Valida los rangos.
     *
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si {@code page < 0} o {@code size}
     *         está fuera de [1, {@value #MAX_SIZE}]
     */
    public PageQuery {
        if (page < 0) {
            throw invalid("page", "El número de página debe ser mayor o igual a 0.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("size", "El tamaño de página debe estar entre 1 y " + MAX_SIZE + ".");
        }
    }

    /**
     * Crea la consulta aplicando los valores por defecto cuando los parámetros no vienen.
     *
     * @param page página (nulo = 0)
     * @param size tamaño (nulo = {@value #DEFAULT_SIZE})
     * @return consulta validada
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si algún valor está fuera de rango
     */
    public static PageQuery of(Integer page, Integer size) {
        return new PageQuery(page == null ? 0 : page, size == null ? DEFAULT_SIZE : size);
    }

    /** Convierte a {@link Pageable} de Spring Data con el orden ya validado por {@link SortParser}. */
    public Pageable toPageable(Sort sort) {
        return PageRequest.of(page, size, sort);
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "Parámetros de paginación inválidos.",
                Map.of(), List.of(new FieldErrorDetail(field, "OUT_OF_RANGE", message)));
    }
}
