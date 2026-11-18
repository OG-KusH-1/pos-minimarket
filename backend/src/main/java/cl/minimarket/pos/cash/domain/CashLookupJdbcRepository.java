package cl.minimarket.pos.cash.domain;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Consultas de lectura directa mediante {@link JdbcClient} para enriquecer respuestas y reportes de
 * caja con datos de maestros permitidos ({@code users}, {@code terminals}, {@code company}).
 */
@Repository
public class CashLookupJdbcRepository {

    private final JdbcClient jdbc;

    public CashLookupJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Datos básicos de usuario para mostrar en respuestas y comprobantes. */
    public record UserInfo(Long id, String username, String fullName) {
    }

    /** Datos básicos de terminal POS. */
    public record TerminalRefInfo(Long id, String code, String name) {
    }

    /** Datos básicos de la empresa para el comprobante de cierre. */
    public record CompanyRefInfo(
            String rut,
            String legalName,
            String tradeName,
            String address,
            String commune,
            String city,
            String phone,
            String email
    ) {
    }

    /**
     * Resuelve en lote los datos básicos de un conjunto de usuarios.
     */
    public Map<Long, UserInfo> findUsersByIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserInfo> result = new HashMap<>();
        jdbc.sql("SELECT id, username, full_name FROM users WHERE id IN (:ids)")
                .param("ids", userIds)
                .query(rs -> {
                    long id = rs.getLong("id");
                    result.put(id, new UserInfo(id, rs.getString("username"), rs.getString("full_name")));
                });
        return result;
    }

    /**
     * Resuelve en lote los datos básicos de un conjunto de terminales.
     */
    public Map<Long, TerminalRefInfo> findTerminalsByIds(Collection<Long> terminalIds) {
        if (terminalIds == null || terminalIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, TerminalRefInfo> result = new HashMap<>();
        jdbc.sql("SELECT id, code, name FROM terminals WHERE id IN (:ids)")
                .param("ids", terminalIds)
                .query(rs -> {
                    long id = rs.getLong("id");
                    result.put(id, new TerminalRefInfo(id, rs.getString("code"), rs.getString("name")));
                });
        return result;
    }

    /**
     * Obtiene los datos de la empresa singleton para el comprobante de cierre.
     */
    public Optional<CompanyRefInfo> findCompanyInfo() {
        return jdbc.sql("""
                SELECT rut, legal_name, trade_name, address, commune, city, phone, email
                FROM company
                ORDER BY id ASC
                LIMIT 1
                """)
                .query((rs, rowNum) -> new CompanyRefInfo(
                        rs.getString("rut"),
                        rs.getString("legal_name"),
                        rs.getString("trade_name"),
                        rs.getString("address"),
                        rs.getString("commune"),
                        rs.getString("city"),
                        rs.getString("phone"),
                        rs.getString("email")
                ))
                .optional();
    }
}
