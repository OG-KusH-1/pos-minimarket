package cl.minimarket.pos.purchasing.supplier.application;

import cl.minimarket.pos.purchasing.supplier.domain.PaymentTermsType;
import cl.minimarket.pos.purchasing.supplier.domain.Supplier;
import cl.minimarket.pos.purchasing.supplier.domain.SupplierRepository;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.rut.Rut;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Casos de uso de proveedores ({@code suppliers}, {@code HU-PRV-01}).
 */
@Service
public class SupplierService {

    private static final int MIN_ISO_WEEKDAY = 1;
    private static final int MAX_ISO_WEEKDAY = 7;

    private final SupplierRepository supplierRepository;
    private final JdbcClient jdbc;

    public SupplierService(SupplierRepository supplierRepository, JdbcClient jdbc) {
        this.supplierRepository = Objects.requireNonNull(supplierRepository, "supplierRepository");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Comando de creación o actualización de proveedor. */
    public record SupplierCommand(
            String rut,
            String legalName,
            String tradeName,
            String businessActivity,
            String address,
            String commune,
            String city,
            String phone,
            String email,
            String contactName,
            String contactPhone,
            String contactEmail,
            PaymentTermsType paymentTermsType,
            Integer paymentTermsDays,
            Integer leadTimeDays,
            List<Integer> deliveryWeekdays,
            String notes,
            Long version
    ) {
    }

    /** Proyección resumida de proveedor para listados paginados. */
    public record SupplierSummaryView(
            Long id,
            String rut,
            String legalName,
            String tradeName,
            int leadTimeDays,
            boolean active
    ) {
    }

    /**
     * Lista proveedores paginados aplicando filtro de texto ({@code q} sobre RUT o razón social /
     * nombre de fantasía) y filtro opcional por estado {@code active}.
     */
    @Transactional(readOnly = true)
    public Page<SupplierSummaryView> list(String q, Boolean active, Pageable pageable) {
        Objects.requireNonNull(pageable, "pageable");
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        Map<String, Object> params = new LinkedHashMap<>();

        if (q != null && !q.isBlank()) {
            String trimmed = q.trim();
            String textPattern = "%" + trimmed.toLowerCase(Locale.ROOT) + "%";
            String rutPattern = "%" + trimmed.replaceAll("[.\\s]", "").toLowerCase(Locale.ROOT) + "%";
            where.append("""
                      AND (f_unaccent(lower(s.legal_name)) ILIKE f_unaccent(:textPattern)
                           OR f_unaccent(lower(COALESCE(s.trade_name, ''))) ILIKE f_unaccent(:textPattern)
                           OR lower(s.rut) LIKE :rutPattern)
                    """);
            params.put("textPattern", textPattern);
            params.put("rutPattern", rutPattern);
        }
        if (active != null) {
            where.append(" AND s.active = :active ");
            params.put("active", active);
        }

        long total = jdbc.sql("SELECT COUNT(*) FROM suppliers s " + where)
                .params(params)
                .query(Long.class)
                .single();

        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        Map<String, Object> pageParams = new LinkedHashMap<>(params);
        pageParams.put("limit", pageable.getPageSize());
        pageParams.put("offset", pageable.getOffset());

        String sql = """
                SELECT s.id, s.rut, s.legal_name, s.trade_name, s.lead_time_days, s.active
                FROM suppliers s
                """ + where + buildOrderBy(pageable.getSort()) + " LIMIT :limit OFFSET :offset ";

        List<SupplierSummaryView> rows = jdbc.sql(sql)
                .params(pageParams)
                .query((rs, rowNum) -> new SupplierSummaryView(
                        rs.getLong("id"),
                        rs.getString("rut"),
                        rs.getString("legal_name"),
                        rs.getString("trade_name"),
                        rs.getInt("lead_time_days"),
                        rs.getBoolean("active")))
                .list();

        return new PageImpl<>(rows, pageable, total);
    }

    /** Obtiene un proveedor por su id o lanza 404 {@code NOT_FOUND}. */
    @Transactional(readOnly = true)
    public Supplier get(Long id) {
        return findOrThrow(id);
    }

    /**
     * Crea un proveedor validando el RUT chileno (422 {@code RUT_INVALID}), unicidad (409
     * {@code DUPLICATED_VALUE}) y días de despacho (1..7 sin repetir).
     */
    @Transactional
    public Supplier create(SupplierCommand cmd) {
        Rut validRut = Rut.of(cmd.rut());
        String normalizedRut = validRut.normalized();
        if (supplierRepository.existsByRut(normalizedRut)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe un proveedor registrado con el RUT " + normalizedRut + ".");
        }

        Supplier supplier = new Supplier();
        applyFields(supplier, cmd, normalizedRut);
        supplier.setActive(true);
        return supplierRepository.save(supplier);
    }

    /**
     * Actualiza un proveedor existente validando versión optimista (si se informa), RUT y
     * unicidad.
     */
    @Transactional
    public Supplier update(Long id, SupplierCommand cmd) {
        Supplier supplier = findOrThrow(id);
        if (cmd.version() != null && !cmd.version().equals(supplier.getVersion())) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "El proveedor fue modificado por otro usuario. Recargue e intente nuevamente.");
        }

        Rut validRut = Rut.of(cmd.rut());
        String normalizedRut = validRut.normalized();
        if (supplierRepository.existsByRutAndIdNot(normalizedRut, id)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe otro proveedor registrado con el RUT " + normalizedRut + ".");
        }

        applyFields(supplier, cmd, normalizedRut);
        return supplierRepository.saveAndFlush(supplier);
    }

    /** Desactiva un proveedor. */
    @Transactional
    public void deactivate(Long id) {
        Supplier supplier = findOrThrow(id);
        if (supplier.isActive()) {
            supplier.setActive(false);
            supplierRepository.save(supplier);
        }
    }

    /** Reactiva un proveedor previamente desactivado. */
    @Transactional
    public void activate(Long id) {
        Supplier supplier = findOrThrow(id);
        if (!supplier.isActive()) {
            supplier.setActive(true);
            supplierRepository.save(supplier);
        }
    }

    private Supplier findOrThrow(Long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Proveedor no encontrado."));
    }

    private static void applyFields(Supplier supplier, SupplierCommand cmd, String normalizedRut) {
        supplier.setRut(normalizedRut);
        supplier.setLegalName(cmd.legalName().trim());
        supplier.setTradeName(trimToNull(cmd.tradeName()));
        supplier.setBusinessActivity(trimToNull(cmd.businessActivity()));
        supplier.setAddress(trimToNull(cmd.address()));
        supplier.setCommune(trimToNull(cmd.commune()));
        supplier.setCity(trimToNull(cmd.city()));
        supplier.setPhone(trimToNull(cmd.phone()));
        supplier.setEmail(trimToNull(cmd.email()));
        supplier.setContactName(trimToNull(cmd.contactName()));
        supplier.setContactPhone(trimToNull(cmd.contactPhone()));
        supplier.setContactEmail(trimToNull(cmd.contactEmail()));
        supplier.setPaymentTermsType(cmd.paymentTermsType());
        supplier.setPaymentTermsDays(cmd.paymentTermsDays() == null ? 0 : cmd.paymentTermsDays());
        supplier.setLeadTimeDays(cmd.leadTimeDays());
        supplier.setDeliveryWeekdays(validateAndConvertWeekdays(cmd.deliveryWeekdays()));
        supplier.setNotes(trimToNull(cmd.notes()));
    }

    private static short[] validateAndConvertWeekdays(List<Integer> rawWeekdays) {
        if (rawWeekdays == null || rawWeekdays.isEmpty()) {
            return new short[0];
        }
        Set<Integer> unique = new LinkedHashSet<>();
        for (Integer day : rawWeekdays) {
            if (day == null || day < MIN_ISO_WEEKDAY || day > MAX_ISO_WEEKDAY) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Los días de despacho deben estar entre 1 (lunes) y 7 (domingo).",
                        Map.of(),
                        List.of(new FieldErrorDetail("deliveryWeekdays", "INVALID_WEEKDAY",
                                "Cada día de despacho debe estar entre 1 y 7.")));
            }
            if (!unique.add(day)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Los días de despacho no pueden repetirse.",
                        Map.of(),
                        List.of(new FieldErrorDetail("deliveryWeekdays", "DUPLICATED_WEEKDAY",
                                "No se permiten días de despacho repetidos.")));
            }
        }
        List<Integer> sorted = unique.stream().sorted().toList();
        short[] result = new short[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            result[i] = sorted.get(i).shortValue();
        }
        return result;
    }

    private static String buildOrderBy(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return " ORDER BY s.legal_name ASC, s.id ASC ";
        }
        List<String> clauses = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = switch (order.getProperty()) {
                case "id" -> "s.id";
                case "rut" -> "s.rut";
                case "legalName" -> "s.legal_name";
                case "tradeName" -> "s.trade_name";
                case "leadTimeDays" -> "s.lead_time_days";
                case "active" -> "s.active";
                case "createdAt" -> "s.created_at";
                default -> null;
            };
            if (column != null) {
                clauses.add(column + (order.isAscending() ? " ASC" : " DESC"));
            }
        }
        if (clauses.isEmpty()) {
            return " ORDER BY s.legal_name ASC, s.id ASC ";
        }
        return " ORDER BY " + String.join(", ", clauses) + ", s.id ASC ";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
