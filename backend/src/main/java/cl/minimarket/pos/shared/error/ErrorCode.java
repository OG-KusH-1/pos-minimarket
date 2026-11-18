package cl.minimarket.pos.shared.error;

import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.Locale;

/**
 * Catálogo completo de códigos de error de la API (F.1 de {@code docs/API.md}, fuente de verdad).
 *
 * <p>Cada valor lleva su estado HTTP según la tabla "Estado HTTP por código" de F.1, un título
 * breve en español (el campo {@code title} de RFC 9457) y un {@code type} URI estable derivado del
 * nombre: {@code https://pos-minimarket/errors/<code-kebab>} (A.5).
 *
 * <p>Reglas de mantenimiento:
 * <ul>
 *   <li>El nombre del valor es el {@code code} público y estable: nunca se renombra ni reutiliza.</li>
 *   <li>Agregar un código exige actualizar primero F.1 y la prueba {@code ErrorCodeHttpStatusTest},
 *       que codifica la tabla esperada y falla ante cualquier divergencia.</li>
 *   <li>Estados: 400 validación; 401 autenticación; 403 permisos o local; 404; 409 conflicto
 *       (versión, idempotencia, estado, duplicado, en uso); 422 regla de negocio; 429 límite de tasa.</li>
 * </ul>
 */
public enum ErrorCode {

    // ---- 400 ----
    /** Entrada inválida (incluye {@code Idempotency-Key} ausente o mal formada). */
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Solicitud inválida"),

    // ---- 401 ----
    /** Falta el access token o está vencido. */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "No autenticado"),
    /** Usuario o clave incorrectos. */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Credenciales inválidas"),
    /** Token de refresh o de override inválido. */
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Token inválido"),

    // ---- 403 ----
    /** El usuario autenticado no tiene el permiso requerido. */
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Acceso denegado"),
    /** El usuario no tiene acceso al local o la terminal indicados. */
    STORE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Sin acceso al local"),

    // ---- 404 ----
    /** El recurso no existe. */
    NOT_FOUND(HttpStatus.NOT_FOUND, "Recurso no encontrado"),
    /** Código de barras, SKU o atajo sin producto asociado. */
    CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "Código no encontrado"),

    // ---- 409 ----
    /** Transición de estado no permitida para la entidad. */
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Transición de estado inválida"),
    /** Misma clave de idempotencia con otro contenido. */
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Conflicto de idempotencia"),
    /** La misma clave de idempotencia sigue en proceso. */
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "Solicitud en proceso"),
    /** Valor único duplicado (SKU, código, atajo, RUT, etc.). */
    DUPLICATED_VALUE(HttpStatus.CONFLICT, "Valor duplicado"),
    /** La entidad está en uso y no puede desactivarse o eliminarse. */
    ENTITY_IN_USE(HttpStatus.CONFLICT, "Entidad en uso"),
    /** La versión enviada no coincide con la vigente (bloqueo optimista). */
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Modificación concurrente"),

    // ---- 422: reglas de negocio ----
    /** Stock insuficiente para la operación. */
    STOCK_INSUFFICIENT(HttpStatus.UNPROCESSABLE_CONTENT, "Stock insuficiente"),
    /** No hay un turno de caja abierto. */
    CASH_SESSION_NOT_OPEN(HttpStatus.UNPROCESSABLE_CONTENT, "Turno de caja no abierto"),
    /** Ya existe un turno de caja abierto en la terminal. */
    CASH_SESSION_ALREADY_OPEN(HttpStatus.UNPROCESSABLE_CONTENT, "Turno de caja ya abierto"),
    /** El retiro excede el efectivo disponible en caja. */
    CASH_OUT_EXCEEDS_AVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT, "Retiro excede el efectivo disponible"),
    /** Descuento bajo $0 o por sobre lo autorizado (RN-11). */
    DISCOUNT_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Descuento excede el límite"),
    /** La acción requiere autorización de un supervisor; extensiones {@code action} y {@code context}. */
    OVERRIDE_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "Se requiere autorización de supervisor"),
    /** El token de override es inválido, está vencido o ya se usó. */
    OVERRIDE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Autorización de supervisor inválida"),
    /** El límite del supervisor no cubre el contexto de la acción. */
    OVERRIDE_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Autorización excede el límite del supervisor"),
    /** Segregación de funciones: el mismo usuario no puede solicitar y autorizar. */
    SEGREGATION_OF_DUTIES(HttpStatus.UNPROCESSABLE_CONTENT, "Segregación de funciones"),
    /** El cliente excede su cupo de crédito. */
    CREDIT_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Cupo de crédito excedido"),
    /** El cliente no tiene crédito habilitado. */
    CREDIT_NOT_ENABLED(HttpStatus.UNPROCESSABLE_CONTENT, "Crédito no habilitado"),
    /** El abono excede la deuda del cliente. */
    PAYMENT_EXCEEDS_DEBT(HttpStatus.UNPROCESSABLE_CONTENT, "Abono excede la deuda"),
    /** El medio de pago rechazó la transacción. */
    PAYMENT_DECLINED(HttpStatus.UNPROCESSABLE_CONTENT, "Pago rechazado"),
    /** Los pagos no calzan con el total a pagar. */
    PAYMENT_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "Pagos no calzan con el total"),
    /** Cuenta bloqueada por intentos fallidos. */
    ACCOUNT_LOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "Cuenta bloqueada"),
    /** PIN bloqueado por intentos fallidos. */
    PIN_LOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "PIN bloqueado"),
    /** La clave no cumple la política de contraseñas. */
    PASSWORD_POLICY(HttpStatus.UNPROCESSABLE_CONTENT, "Contraseña no cumple la política"),
    /** La terminal no existe, está inactiva o no pertenece al local. */
    TERMINAL_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Terminal inválida"),
    /** RUT con formato o dígito verificador inválido (RN-30). */
    RUT_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "RUT inválido"),
    /** Código de barras con formato o dígito verificador inválido (RN-30). */
    BARCODE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Código de barras inválido"),
    /** El producto está inactivo. */
    PRODUCT_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "Producto inactivo"),
    /** El producto no tiene precio de venta. */
    PRICE_NOT_SET(HttpStatus.UNPROCESSABLE_CONTENT, "Producto sin precio"),
    /** Producto restringido: falta confirmar la mayoría de edad. */
    AGE_CONFIRMATION_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "Falta confirmar la mayoría de edad"),
    /** Producto con control de lotes: falta indicar el lote. */
    LOT_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "Lote requerido"),
    /** La recepción excede lo pedido en la orden de compra. */
    RECEIPT_EXCEEDS_ORDER(HttpStatus.UNPROCESSABLE_CONTENT, "Recepción excede la orden"),
    /** La anulación de la venta no está permitida. */
    VOID_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT, "Anulación no permitida"),
    /** Venció el plazo de devolución. */
    RETURN_WINDOW_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT, "Plazo de devolución vencido"),
    /** La cantidad a devolver excede lo vendido y no devuelto. */
    RETURN_QUANTITY_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Cantidad a devolver excedida"),
    /** Se alcanzó el máximo de ventas suspendidas. */
    SUSPENDED_LIMIT_REACHED(HttpStatus.UNPROCESSABLE_CONTENT, "Límite de ventas suspendidas alcanzado"),
    /** Archivo inválido (tipo, contenido o tamaño). */
    FILE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Archivo inválido"),
    /** El archivo de importación contiene errores. */
    IMPORT_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Importación inválida"),

    // ---- Protocolo HTTP y errores técnicos ----
    /** Método HTTP no permitido para el recurso. */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido"),
    /** No se puede producir una respuesta en el formato solicitado en {@code Accept}. */
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Formato de respuesta no aceptable"),
    /** El {@code Content-Type} de la solicitud no está soportado. */
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de contenido no soportado"),
    /** Error no controlado; la respuesta no incluye detalles internos. */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno"),

    // ---- 429 ----
    /** Se superó el límite de tasa. */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Demasiadas solicitudes");

    /** Prefijo común de los URI {@code type} (A.5). */
    public static final String TYPE_BASE_URI = "https://pos-minimarket/errors/";

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    /** Estado HTTP asociado, según la tabla de F.1. */
    public HttpStatus status() {
        return status;
    }

    /** Título breve en español para el campo {@code title} del problema. */
    public String title() {
        return title;
    }

    /** URI estable del campo {@code type}: {@code https://pos-minimarket/errors/<code-kebab>}. */
    public URI type() {
        return URI.create(TYPE_BASE_URI + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
