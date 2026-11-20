package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.notification.EmailOutboxApi;
import cl.minimarket.pos.notification.EmailOutboxApi.EmailMessage;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.CompanyInfo;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Caso de uso de envío del comprobante de venta por correo ({@code POST /sales/{id}/email},
 * {@code API.md} F.2, HU-POS-11).
 *
 * <p>Encola el correo en la outbox de {@code notification} ({@link EmailOutboxApi#enqueue}) dentro
 * de la transacción del caso de uso, siguiendo el patrón outbox (ADR-10): la operación responde
 * aunque el servidor SMTP esté caído, porque el despacho real ocurre después del commit y se
 * reintenta mediante el job programado del módulo {@code notification}.
 */
@Service
public class SaleEmailService {

    /** Plantilla lógica del comprobante de venta en {@code EmailTemplateRenderer}. */
    private static final String TICKET_TEMPLATE = "sale-ticket";

    private final SaleRepository saleRepository;
    private final SaleJdbcRepository jdbcRepository;
    private final EmailOutboxApi emailOutboxApi;

    public SaleEmailService(
            SaleRepository saleRepository,
            SaleJdbcRepository jdbcRepository,
            EmailOutboxApi emailOutboxApi
    ) {
        this.saleRepository = Objects.requireNonNull(saleRepository, "saleRepository");
        this.jdbcRepository = Objects.requireNonNull(jdbcRepository, "jdbcRepository");
        this.emailOutboxApi = Objects.requireNonNull(emailOutboxApi, "emailOutboxApi");
    }

    /**
     * Encola el envío del comprobante de una venta del local activo.
     *
     * <p>Valida que la venta exista en el local del contexto ({@code X-Store-Id}) y que el
     * destinatario venga informado con formato de correo (lo valida también la capa web); el
     * contenido del correo se arma como resumen del comprobante (número, fecha de negocio,
     * empresa y total a pagar).
     *
     * @param saleId  identificador de la venta
     * @param emailTo dirección de correo del destinatario (obligatoria)
     * @throws BusinessException 404 {@code NOT_FOUND} si la venta no existe en el local;
     *         400 {@code VALIDATION_ERROR} si falta el destinatario
     */
    @Transactional
    public void sendTicketByEmail(Long saleId, String emailTo) {
        Long storeId = StoreContext.require().storeId();
        String recipient = SaleSecuritySupport.trimToNull(emailTo);
        if (recipient == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Debe informar el correo electrónico del destinatario.",
                    Map.of(),
                    List.of(new FieldErrorDetail("email", "NOT_BLANK", "El correo es obligatorio."))
            );
        }

        Sale sale = saleRepository.findByIdAndStoreId(saleId, storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Venta no encontrada."));

        String companyName = jdbcRepository.findCompanyInfo()
                .map(CompanyInfo::tradeName)
                .filter(name -> name != null && !name.isBlank())
                .orElse("Minimarket");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("companyName", companyName);
        payload.put("saleNumber", sale.getNumber());
        payload.put("businessDate", sale.getBusinessDate().toString());
        payload.put("totalPayable", sale.getTotalPayable());
        payload.put("customerId", sale.getCustomerId());

        emailOutboxApi.enqueue(new EmailMessage(
                recipient,
                "Comprobante de venta " + sale.getNumber(),
                TICKET_TEMPLATE,
                payload,
                null
        ));
    }
}
