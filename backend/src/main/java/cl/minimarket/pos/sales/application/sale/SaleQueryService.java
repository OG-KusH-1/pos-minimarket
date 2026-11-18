package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.organization.SettingsApi;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.CompanyInfo;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.SaleSummaryRow;
import cl.minimarket.pos.security.AuthorizationRef;
import cl.minimarket.pos.security.OverrideAction;
import cl.minimarket.pos.security.SupervisorOverrideApi;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Casos de uso de consulta, emisión de ticket térmico y reimpresión autorizada de ventas
 * ({@code API.md} F.2).
 *
 * <p>Aplica alcance "P" en lectura y ticket para cajeros: quienes no posean permisos ampliados de
 * ventas/reportes solo acceden a sus propias ventas; las ventas ajenas responden {@code 404 NOT_FOUND}.
 */
@Service
public class SaleQueryService {

    private static final String SETTING_HEADER_LINES = "ticket.header_lines";
    private static final String SETTING_FOOTER_LINES = "ticket.footer_lines";
    private static final String SETTING_SHOW_TAX_BREAKDOWN = "ticket.show_tax_breakdown";
    private static final String SETTING_PAPER_WIDTH_MM = "ticket.paper_width_mm";
    private static final int DEFAULT_PAPER_WIDTH_MM = 80;

    private final SaleRepository saleRepository;
    private final SaleJdbcRepository jdbcRepository;
    private final SaleDetailAssembler detailAssembler;
    private final SettingsApi settingsApi;
    private final SupervisorOverrideApi supervisorOverrideApi;
    private final AuditService auditService;

    public SaleQueryService(
            SaleRepository saleRepository,
            SaleJdbcRepository jdbcRepository,
            SaleDetailAssembler detailAssembler,
            SettingsApi settingsApi,
            SupervisorOverrideApi supervisorOverrideApi,
            AuditService auditService
    ) {
        this.saleRepository = saleRepository;
        this.jdbcRepository = jdbcRepository;
        this.detailAssembler = detailAssembler;
        this.settingsApi = settingsApi;
        this.supervisorOverrideApi = supervisorOverrideApi;
        this.auditService = auditService;
    }

    /**
     * Lista ventas del local activo aplicando filtros y alcance P cuando corresponde.
     */
    @Transactional(readOnly = true)
    public Page<SaleSummaryRow> list(SaleFilter filter, Pageable pageable) {
        Long storeId = StoreContext.require().storeId();
        Long scopeCashierUserId = SaleSecuritySupport.hasFullSaleReadScope()
                ? null
                : SaleSecuritySupport.requireCurrentUserId();
        return jdbcRepository.findPage(filter, scopeCashierUserId, storeId, pageable);
    }

    /**
     * Obtiene el detalle completo de una venta por su id respetando el local activo y alcance P.
     */
    @Transactional(readOnly = true)
    public SaleDetail getById(Long id) {
        return detailAssembler.load(findAccessibleSaleById(id));
    }

    /**
     * Obtiene el detalle completo de una venta por su folio interno ({@code number}) respetando
     * el local activo y alcance P.
     */
    @Transactional(readOnly = true)
    public SaleDetail getByNumber(String number) {
        Long storeId = StoreContext.require().storeId();
        String trimmed = SaleSecuritySupport.trimToNull(number);
        if (trimmed == null) {
            throw saleNotFound();
        }
        Sale sale = saleRepository.findByNumberAndStoreId(trimmed, storeId)
                .orElseThrow(SaleQueryService::saleNotFound);
        enforceReadScope(sale);
        return detailAssembler.load(sale);
    }

    /**
     * Obtiene la representación del ticket térmico de 80 mm para su impresión inicial (libre).
     */
    @Transactional(readOnly = true)
    public TicketDetail getTicket(Long id) {
        Sale sale = findAccessibleSaleById(id);
        int reprints = jdbcRepository.countReprints(sale.getId());
        String copyLabel = reprints > 0 ? "COPIA" : null;
        return buildTicketDetail(sale, reprints, copyLabel);
    }

    /**
     * Reimprime el ticket térmico de una venta ({@code POST /sales/{id}/reprint}), validando
     * autorización de supervisor ({@code RECEIPT_REPRINT}) cuando el solicitante no posee
     * {@code sales:receipt:reprint} y auditando {@code RECEIPT_REPRINTED}.
     */
    @Transactional
    public TicketDetail reprintTicket(Long id, String overrideToken) {
        Sale sale = findAccessibleSaleById(id);
        Long currentUserId = SaleSecuritySupport.requireCurrentUserId();
        boolean hasReprintPermission = SaleSecuritySupport.hasAuthority(Permissions.SALES_RECEIPT_REPRINT);

        Long authorizationId = null;
        Map<String, Object> overrideContext = Map.of(
                "saleId", sale.getId(),
                "number", sale.getNumber()
        );
        String trimmedToken = SaleSecuritySupport.trimToNull(overrideToken);
        if (!hasReprintPermission) {
            if (trimmedToken == null) {
                throw new BusinessException(
                        ErrorCode.OVERRIDE_REQUIRED,
                        "La reimpresión del ticket requiere autorización de supervisor.",
                        Map.of(
                                "action", OverrideAction.RECEIPT_REPRINT.name(),
                                "context", overrideContext
                        )
                );
            }
            AuthorizationRef authRef = supervisorOverrideApi.consume(
                    trimmedToken,
                    OverrideAction.RECEIPT_REPRINT,
                    supervisorOverrideApi.canonicalContextHash(overrideContext),
                    currentUserId,
                    "sale",
                    String.valueOf(sale.getId())
            );
            authorizationId = authRef.authorizationId();
        } else if (trimmedToken != null) {
            AuthorizationRef authRef = supervisorOverrideApi.consume(
                    trimmedToken,
                    OverrideAction.RECEIPT_REPRINT,
                    supervisorOverrideApi.canonicalContextHash(overrideContext),
                    currentUserId,
                    "sale",
                    String.valueOf(sale.getId())
            );
            authorizationId = authRef.authorizationId();
        }

        int newReprintCount = jdbcRepository.countReprints(sale.getId()) + 1;
        auditService.record(new AuditEvent(
                "RECEIPT_REPRINTED",
                "sale",
                String.valueOf(sale.getId()),
                null,
                Map.of(
                        "saleNumber", sale.getNumber(),
                        "reprintCount", newReprintCount
                ),
                null,
                authorizationId
        ));

        return buildTicketDetail(sale, newReprintCount, "COPIA");
    }

    private Sale findAccessibleSaleById(Long id) {
        Long storeId = StoreContext.require().storeId();
        Sale sale = saleRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(SaleQueryService::saleNotFound);
        enforceReadScope(sale);
        return sale;
    }

    private static void enforceReadScope(Sale sale) {
        if (!SaleSecuritySupport.hasFullSaleReadScope()) {
            Long currentUserId = SaleSecuritySupport.requireCurrentUserId();
            if (!sale.getCashierUserId().equals(currentUserId)) {
                throw saleNotFound();
            }
        }
    }

    private TicketDetail buildTicketDetail(Sale sale, int reprintCount, String copyLabel) {
        SaleDetail detail = detailAssembler.load(sale);
        CompanyInfo company = jdbcRepository.findCompanyInfo()
                .orElse(new CompanyInfo("Minimarket", "", "", ""));
        Long storeId = sale.getStoreId();

        String[] headerArr = settingsApi.get(SETTING_HEADER_LINES, storeId, String[].class);
        String[] footerArr = settingsApi.get(SETTING_FOOTER_LINES, storeId, String[].class);
        Boolean showTax = settingsApi.get(SETTING_SHOW_TAX_BREAKDOWN, storeId, Boolean.class);
        Integer paperWidth = settingsApi.get(SETTING_PAPER_WIDTH_MM, storeId, Integer.class);

        return new TicketDetail(
                detail,
                company,
                headerArr != null ? Arrays.asList(headerArr) : List.of(),
                footerArr != null ? Arrays.asList(footerArr) : List.of(),
                showTax == null || showTax,
                paperWidth != null ? paperWidth : DEFAULT_PAPER_WIDTH_MM,
                reprintCount,
                copyLabel
        );
    }

    private static BusinessException saleNotFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "Venta no encontrada.");
    }
}
