package cl.minimarket.pos.shared.web;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Interpreta el parámetro {@code sort=campo,asc|desc} (repetible) contra una lista blanca propia
 * de cada endpoint (A.5), de modo que el cliente nunca pueda ordenar por una propiedad interna.
 *
 * <p>Cada endpoint crea su parser una vez (constante estática) con los campos que expone:
 * <pre>
 * private static final SortParser SORT = SortParser.allowing("name", "sku")
 *         .withDefault(Sort.by("name"));
 * </pre>
 * Si el nombre público difiere de la propiedad de la entidad se usa {@link #of(Map)}
 * (clave = nombre en la API, valor = propiedad JPA o columna).
 *
 * <p>Reglas: la dirección es opcional y por defecto {@code asc}; se acepta {@code ASC}/{@code DESC}
 * en cualquier combinación de mayúsculas; un campo repetido conserva su primera aparición. Un
 * campo fuera de la lista blanca, una dirección inválida o un criterio vacío producen 400
 * {@code VALIDATION_ERROR} con un elemento en {@code errors[]} ({@code field = "sort"}). La
 * instancia es inmutable y segura entre hilos.
 */
public final class SortParser {

    /** Código del elemento de {@code errors[]} cuando el campo no está en la lista blanca. */
    public static final String FIELD_NOT_ALLOWED = "SORT_FIELD_NOT_ALLOWED";

    /** Código del elemento de {@code errors[]} cuando la dirección o el formato son inválidos. */
    public static final String INVALID_FORMAT = "SORT_INVALID_FORMAT";

    private static final String PARAMETER = "sort";

    private final Map<String, String> allowed;
    private final Sort defaultSort;

    private SortParser(Map<String, String> allowed, Sort defaultSort) {
        this.allowed = Map.copyOf(allowed);
        this.defaultSort = defaultSort;
    }

    /** Parser cuyos campos públicos coinciden con las propiedades ordenables. */
    public static SortParser allowing(String... fields) {
        Map<String, String> identity = new LinkedHashMap<>();
        for (String field : fields) {
            identity.put(field, field);
        }
        return of(identity);
    }

    /**
     * Parser con alias.
     *
     * @param allowedFields nombre público en la API a propiedad real de ordenamiento
     * @return parser sin orden por defecto ({@link Sort#unsorted()})
     */
    public static SortParser of(Map<String, String> allowedFields) {
        return new SortParser(Objects.requireNonNull(allowedFields, "allowedFields"), Sort.unsorted());
    }

    /** Devuelve un parser equivalente que aplica {@code defaultSort} cuando no llega {@code sort}. */
    public SortParser withDefault(Sort defaultSort) {
        return new SortParser(allowed, Objects.requireNonNull(defaultSort, "defaultSort"));
    }

    /**
     * Convierte los valores del parámetro {@code sort} en un {@link Sort}.
     *
     * @param rawSort valores recibidos ({@code null} o vacío = sin orden explícito)
     * @return orden validado, con los nombres de propiedad reales; el orden por defecto si no se envió
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si algún criterio es inválido
     */
    public Sort parse(List<String> rawSort) {
        if (rawSort == null || rawSort.isEmpty()) {
            return defaultSort;
        }
        Map<String, Sort.Order> orders = new LinkedHashMap<>();
        for (String raw : rawSort) {
            Sort.Order order = parseOne(raw);
            orders.putIfAbsent(order.getProperty(), order);
        }
        return Sort.by(new ArrayList<>(orders.values()));
    }

    private Sort.Order parseOne(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid(INVALID_FORMAT, "El criterio de orden no puede estar vacío.");
        }
        String[] parts = raw.split(",", -1);
        if (parts.length > 2) {
            throw invalid(INVALID_FORMAT, "El criterio de orden debe tener el formato campo,asc|desc.");
        }
        String field = parts[0].trim();
        String property = allowed.get(field);
        if (property == null) {
            throw invalid(FIELD_NOT_ALLOWED, "No se puede ordenar por '" + sanitize(field) + "'. Campos permitidos: "
                    + String.join(", ", allowed.keySet().stream().sorted().toList()) + ".");
        }
        return new Sort.Order(parseDirection(parts.length == 2 ? parts[1].trim() : "asc"), property);
    }

    private static Sort.Direction parseDirection(String raw) {
        if ("asc".equalsIgnoreCase(raw)) {
            return Sort.Direction.ASC;
        }
        if ("desc".equalsIgnoreCase(raw)) {
            return Sort.Direction.DESC;
        }
        throw invalid(INVALID_FORMAT, "La dirección de orden debe ser asc o desc.");
    }

    /** Evita reflejar en el mensaje entradas largas o con caracteres de control. */
    private static String sanitize(String value) {
        String clean = value.replaceAll("[^\\p{L}\\p{N}_.\\-]", "?");
        return clean.length() > 50 ? clean.substring(0, 50) + "…" : clean;
    }

    private static BusinessException invalid(String code, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "Parámetro de orden inválido.", Map.of(),
                List.of(new FieldErrorDetail(PARAMETER, code, message)));
    }
}
