package cl.minimarket.pos.reporting.cash.application;

import cl.minimarket.pos.reporting.cash.CashDifferenceRow;
import cl.minimarket.pos.reporting.cash.infrastructure.CashDifferenceReportRepository;
import cl.minimarket.pos.reporting.shared.ReportFilter;
import cl.minimarket.pos.reporting.shared.ReportResponse;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Servicio de aplicación para el reporte de diferencias de caja ({@code HU-REP-09}).
 *
 * <p>Permite filtrar turnos cerrados por cajero y por existencia de diferencia distinta de cero
 * ({@code withDifference}).
 */
@Service
public class CashDifferenceReportService {

    private final CashDifferenceReportRepository repository;
    private final BusinessClock clock;

    public CashDifferenceReportService(CashDifferenceReportRepository repository, BusinessClock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Genera el reporte de diferencias de caja para el filtro indicado.
     *
     * @param filter         filtro obligatorio de fechas y locales
     * @param cashierId      identificador opcional de cajero
     * @param withDifference {@code true} para solo turnos con diferencia distinta de 0;
     *                       {@code false} para solo cuadres exactos; {@code null} para todos
     * @return respuesta con filas y fila de totales
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si {@code cashierId <= 0}
     */
    @Transactional(readOnly = true)
    public ReportResponse<CashDifferenceRow> generate(
            ReportFilter filter,
            Long cashierId,
            Boolean withDifference) {
        Objects.requireNonNull(filter, "filter");
        filter.requireRange();

        if (cashierId != null && cashierId <= 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El identificador de cajero debe ser un entero positivo.",
                    Map.of(),
                    List.of(new FieldErrorDetail("cashierId", "INVALID_ID",
                            "cashierId debe ser mayor que 0.")));
        }

        List<Long> effectiveStoreIds = filter.storeIds().isEmpty()
                ? List.of(StoreContext.require().storeId())
                : filter.storeIds();
        ReportFilter effectiveFilter = new ReportFilter(filter.from(), filter.to(), effectiveStoreIds);

        List<CashDifferenceRow> rows = repository.findCashDifferences(
                effectiveFilter.from(),
                effectiveFilter.to(),
                effectiveStoreIds,
                cashierId,
                withDifference);

        BigDecimal totalExpected = BigDecimal.ZERO;
        BigDecimal totalCounted = BigDecimal.ZERO;
        BigDecimal totalDifference = BigDecimal.ZERO;
        for (CashDifferenceRow row : rows) {
            totalExpected = totalExpected.add(row.expected());
            totalCounted = totalCounted.add(row.counted());
            totalDifference = totalDifference.add(row.difference());
        }

        CashDifferenceRow totals = new CashDifferenceRow(
                null,
                "TOTAL",
                null,
                "Total",
                null,
                Rounding.clp(totalExpected),
                Rounding.clp(totalCounted),
                Rounding.clp(totalDifference),
                null);

        return new ReportResponse<>(effectiveFilter, clock.now(), rows, totals);
    }
}
