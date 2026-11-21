package cl.minimarket.pos.reporting.dashboard.web;

import cl.minimarket.pos.reporting.dashboard.application.DashboardService;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.security.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Endpoint REST del dashboard ejecutivo diario ({@code API.md} F.2, {@code HU-REP-01}).
 *
 * <p>Es store-scoped (exige {@code X-Store-Id}, sin {@code @GlobalEndpoint} ni
 * {@code @RequiresTerminal}), consolidando por defecto el local activo o los locales solicitados en
 * {@code storeIds} validados contra los asignados al usuario.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reporting")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = Objects.requireNonNull(dashboardService, "dashboardService");
    }

    /**
     * Obtiene los indicadores diarios del dashboard para la fecha y locales solicitados.
     */
    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority(T(cl.minimarket.pos.shared.security.Permissions).REPORT_DASHBOARD_READ)")
    @Operation(
            summary = "Dashboard del día",
            description = "Permiso: report:dashboard:read. Resume ventas netas, transacciones, ticket promedio, "
                    + "variación vs día anterior, serie por hora, top 10 productos, mix de pagos y alertas.")
    @ApiResponse(responseCode = "200", description = "Dashboard diario generado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR si date o storeIds son inválidos")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED o STORE_ACCESS_DENIED")
    public DashboardResponse dashboard(
            @Parameter(description = "Fecha de negocio (YYYY-MM-DD); por defecto hoy en la zona de negocio.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "Locales a consultar (opcional; por defecto el local activo de X-Store-Id).")
            @RequestParam(required = false) List<Long> storeIds) {
        Long activeStoreId = StoreContext.require().storeId();
        Long userId = CurrentUserId.find().orElse(null);
        boolean allStoreAccess = hasAuthority(Permissions.SECURITY_STORE_ALL_ACCESS);
        return DashboardResponse.from(
                dashboardService.getDashboard(date, storeIds, activeStoreId, userId, allStoreAccess));
    }

    private static boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
