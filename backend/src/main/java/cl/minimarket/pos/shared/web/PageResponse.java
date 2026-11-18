package cl.minimarket.pos.shared.web;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.function.Function;

/**
 * Respuesta paginada estándar de todo {@code GET} de colección (A.5):
 * {@code {content, page, size, totalElements, totalPages, sort}}.
 *
 * @param content elementos de la página actual
 * @param page número de página, desde 0
 * @param size tamaño de página solicitado
 * @param totalElements total de elementos que cumplen el filtro
 * @param totalPages total de páginas
 * @param sort criterios de orden aplicados, con el formato de la query ({@code "name,asc"})
 * @param <T> tipo del elemento ({@code …Summary} o {@code …Response})
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages,
                              List<String> sort) {

    /** Copia defensiva de las listas. */
    public PageResponse {
        content = List.copyOf(content);
        sort = List.copyOf(sort);
    }

    /** Envuelve una página de Spring Data cuyos elementos ya son el DTO de respuesta. */
    public static <T> PageResponse<T> of(Page<T> page) {
        return from(page, Function.identity());
    }

    /**
     * Envuelve una página de Spring Data convirtiendo cada elemento (p. ej. entidad a DTO).
     *
     * @param page página origen
     * @param mapper conversión de elemento
     * @param <S> tipo origen
     * @param <T> tipo de respuesta
     * @return página de respuesta con el orden aplicado
     */
    public static <S, T> PageResponse<T> from(Page<S> page, Function<? super S, ? extends T> mapper) {
        List<T> content = page.getContent().stream().<T>map(mapper).toList();
        return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), describe(page.getSort()));
    }

    private static List<String> describe(Sort sort) {
        return sort.stream()
                .map(order -> order.getProperty() + "," + order.getDirection().name().toLowerCase())
                .toList();
    }
}
