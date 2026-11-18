package cl.minimarket.pos.inventory.application.transfer;

import cl.minimarket.pos.inventory.application.transfer.TransferLookupRepository.TransferFilter;
import cl.minimarket.pos.inventory.application.transfer.TransferLookupRepository.TransferHeaderRow;
import cl.minimarket.pos.inventory.application.transfer.TransferLookupRepository.TransferLineRow;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Servicio de lectura paginada y de detalle para transferencias entre locales.
 *
 * <p>Aplica el aislamiento por local activo ({@code X-Store-Id}): una transferencia solo es visible
 * para su local de origen o su local de destino.
 */
@Service
public class TransferQueryService {

    private final TransferLookupRepository lookup;
    private final BusinessClock clock;

    public TransferQueryService(TransferLookupRepository lookup, BusinessClock clock) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Proyección de referencia compacta a local. */
    public record StoreSummary(Long id, String code, String name) {}

    /** Proyección de referencia compacta a usuario. */
    public record UserSummary(Long id, String username, String fullName) {}

    /** Vista completa de una línea de transferencia para la capa web. */
    public record TransferLineDetail(
            Long id,
            Long productId,
            String sku,
            String name,
            String lotNumber,
            LocalDate expiryDate,
            BigDecimal qtySent,
            BigDecimal qtyReceived,
            String differenceReason) {}

    /** Vista completa de una transferencia entre locales para la capa web. */
    public record TransferDetail(
            Long id,
            String number,
            StoreSummary origin,
            StoreSummary destination,
            TransferStatus status,
            Instant sentAt,
            UserSummary sentBy,
            Instant receivedAt,
            UserSummary receivedBy,
            String notes,
            Long version,
            List<TransferLineDetail> lines) {}

    /**
     * Lista las transferencias donde el local activo participa como origen o destino.
     *
     * @param direction dirección opcional ({@link TransferDirection#IN}, {@link
     *     TransferDirection#OUT} o {@code null} para ambas).
     * @param status estado opcional.
     * @param from fecha inicial inclusiva (opcional).
     * @param to fecha final inclusiva (opcional).
     * @param pageable configuración de página y ordenamiento.
     * @return página de transferencias con sus líneas hidratadas.
     */
    @Transactional(readOnly = true)
    public Page<TransferDetail> list(
            TransferDirection direction,
            TransferStatus status,
            LocalDate from,
            LocalDate to,
            Pageable pageable) {
        Long storeId = StoreContext.require().storeId();
        Instant fromInstant = from != null ? clock.startOfDay(from) : null;
        Instant toExclusive = to != null ? clock.startOfDay(to.plusDays(1)) : null;

        Page<TransferHeaderRow> headers =
                lookup.findHeaders(
                        storeId,
                        new TransferFilter(direction, status, fromInstant, toExclusive),
                        pageable);
        if (headers.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, headers.getTotalElements());
        }

        List<Long> ids = headers.getContent().stream().map(TransferHeaderRow::id).toList();
        Map<Long, List<TransferLineRow>> linesByTransfer = lookup.findLinesByTransferIds(ids);

        List<TransferDetail> content =
                headers.getContent().stream()
                        .map(h -> toDetail(h, linesByTransfer.getOrDefault(h.id(), List.of())))
                        .toList();
        return new PageImpl<>(content, pageable, headers.getTotalElements());
    }

    /**
     * Obtiene el detalle de una transferencia verificando que pertenezca al local activo como
     * origen o destino.
     *
     * @param id identificador de la transferencia.
     * @return detalle completo de la transferencia.
     * @throws BusinessException con {@link ErrorCode#NOT_FOUND} si no existe o no involucra al
     *     local activo.
     */
    @Transactional(readOnly = true)
    public TransferDetail getById(Long id) {
        Long storeId = StoreContext.require().storeId();
        TransferHeaderRow header =
                lookup.findHeaderById(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND,
                                                "Transferencia no encontrada"));

        if (!storeId.equals(header.originStoreId())
                && !storeId.equals(header.destinationStoreId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Transferencia no encontrada");
        }

        List<TransferLineRow> lines =
                lookup.findLinesByTransferIds(List.of(id)).getOrDefault(id, List.of());
        return toDetail(header, lines);
    }

    /**
     * Carga el detalle por ID sin revalidar contexto de local (uso interno tras mutaciones en la
     * misma transacción).
     */
    TransferDetail loadDetailInternal(Long id) {
        TransferHeaderRow header =
                lookup.findHeaderById(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND,
                                                "Transferencia no encontrada"));
        List<TransferLineRow> lines =
                lookup.findLinesByTransferIds(List.of(id)).getOrDefault(id, List.of());
        return toDetail(header, lines);
    }

    private TransferDetail toDetail(TransferHeaderRow h, List<TransferLineRow> lineRows) {
        UserSummary sentBy =
                h.sentById() != null
                        ? new UserSummary(h.sentById(), h.sentByUsername(), h.sentByFullName())
                        : null;
        UserSummary receivedBy =
                h.receivedById() != null
                        ? new UserSummary(
                                h.receivedById(), h.receivedByUsername(), h.receivedByFullName())
                        : null;
        List<TransferLineDetail> lines =
                lineRows.stream()
                        .map(
                                l ->
                                        new TransferLineDetail(
                                                l.id(),
                                                l.productId(),
                                                l.sku(),
                                                l.productName(),
                                                l.lotNumber(),
                                                l.expiryDate(),
                                                l.qtySent(),
                                                l.qtyReceived(),
                                                l.differenceReason()))
                        .toList();
        return new TransferDetail(
                h.id(),
                h.number(),
                new StoreSummary(h.originStoreId(), h.originStoreCode(), h.originStoreName()),
                new StoreSummary(
                        h.destinationStoreId(),
                        h.destinationStoreCode(),
                        h.destinationStoreName()),
                h.status(),
                h.sentAt(),
                sentBy,
                h.receivedAt(),
                receivedBy,
                h.notes(),
                h.version(),
                lines);
    }
}
