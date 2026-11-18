package cl.minimarket.pos.shared.sequence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Genera folios correlativos <b>sin huecos ni duplicados</b> (RN-29) a partir de
 * {@code document_sequences}.
 *
 * <p><b>Formato del folio:</b> {@code {prefix}-{valor con 8 dígitos}}, p. ej.
 * {@code L01-V-00001234}. El {@code prefix} lo guarda la fila; si la fila no existe, se crea al
 * primer uso con el prefijo {@code <código del local>-<código del tipo>} (p. ej. {@code L01-V}) o,
 * en secuencias globales, solo el código del tipo (p. ej. {@code BOL}). Si el prefijo resultante
 * supera los 10 caracteres de la columna se usa {@code S<id del local>-<código del tipo>}. Pasado
 * el valor 99.999.999 el número simplemente crece en dígitos.
 *
 * <p><b>Garantía de "sin huecos":</b> la fila se bloquea con {@code SELECT ... FOR UPDATE} y se
 * incrementa <i>dentro de la transacción del llamador</i>. Si esa transacción hace rollback (la
 * venta falla), el incremento se revierte con ella y el folio vuelve a estar disponible; si hace
 * commit, el folio queda consumido. Por eso {@link #next} exige una transacción en curso
 * ({@link Propagation#MANDATORY}): usarlo sin transacción (o en una transacción propia) rompería
 * la garantía. El costo es que las transacciones que piden folio del mismo tipo y local se
 * serializan; conviene pedir el folio lo más tarde posible dentro del caso de uso.
 *
 * <p>Ordenación de bloqueos: un caso de uso que pida varios folios debe hacerlo siempre en el mismo
 * orden para evitar deadlocks.
 *
 * <p>Se usa {@link JdbcClient} y no una entidad JPA porque el bloqueo pesimista y el
 * {@code INSERT ... ON CONFLICT} son más directos y predecibles en SQL, y la tabla no necesita el
 * ciclo de vida de una entidad.
 */
@Service
public class SequenceService {

    /** Largo máximo de {@code document_sequences.prefix}. */
    static final int PREFIX_MAX_LENGTH = 10;

    private final JdbcClient jdbc;

    /**
     * @param jdbc cliente JDBC que participa de la transacción del llamador
     */
    public SequenceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reserva y devuelve el siguiente folio.
     *
     * @param type tipo de correlativo
     * @param storeId local dueño del correlativo; debe ser {@code null} para tipos globales y no
     *        nulo para tipos por local
     * @return el folio formateado, p. ej. {@code L01-V-00000001}
     * @throws IllegalArgumentException si {@code storeId} no corresponde al alcance del tipo, o si el
     *         local no existe al crear la secuencia por primera vez
     * @throws org.springframework.transaction.IllegalTransactionStateException si no hay una
     *         transacción activa
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(SequenceType type, Long storeId) {
        Objects.requireNonNull(type, "type");
        validateScope(type, storeId);
        Row row = lockRow(type, storeId).orElseGet(() -> {
            createRow(type, storeId);
            return lockRow(type, storeId).orElseThrow(
                    () -> new IllegalStateException("No se pudo crear la secuencia " + type + " del local " + storeId));
        });
        jdbc.sql("UPDATE document_sequences SET next_value = next_value + 1, updated_at = now() WHERE id = :id")
                .param("id", row.id())
                .update();
        return format(row.prefix(), row.nextValue());
    }

    /**
     * Da formato a un folio.
     *
     * @param prefix prefijo de la secuencia
     * @param value número correlativo (>= 1)
     * @return {@code prefix-00000000}
     */
    static String format(String prefix, long value) {
        return String.format("%s-%08d", prefix, value);
    }

    private static void validateScope(SequenceType type, Long storeId) {
        if (type.scope() == SequenceType.Scope.GLOBAL && storeId != null) {
            throw new IllegalArgumentException("La secuencia " + type + " es global: storeId debe ser null");
        }
        if (type.scope() == SequenceType.Scope.STORE && storeId == null) {
            throw new IllegalArgumentException("La secuencia " + type + " es por local: storeId es obligatorio");
        }
    }

    private Optional<Row> lockRow(SequenceType type, Long storeId) {
        String sql = "SELECT id, prefix, next_value FROM document_sequences WHERE sequence_type = :type AND "
                + (storeId == null ? "store_id IS NULL" : "store_id = :storeId") + " FOR UPDATE";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("type", type.name());
        if (storeId != null) {
            statement = statement.param("storeId", storeId);
        }
        return statement
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("prefix"), rs.getLong("next_value")))
                .optional();
    }

    /** Crea la fila si falta; {@code ON CONFLICT DO NOTHING} tolera que otra transacción la cree a la vez. */
    private void createRow(SequenceType type, Long storeId) {
        String prefix = storeId == null ? type.code() : storePrefix(type, storeId);
        // El índice único es sobre (sequence_type, COALESCE(store_id, 0)); el conflicto se infiere de él.
        jdbc.sql("""
                INSERT INTO document_sequences (sequence_type, store_id, prefix)
                VALUES (:type, CAST(:storeId AS bigint), :prefix)
                ON CONFLICT (sequence_type, (COALESCE(store_id, 0))) DO NOTHING
                """)
                .params(Map.of("type", type.name(), "prefix", prefix))
                .param("storeId", storeId)
                .update();
    }

    private String storePrefix(SequenceType type, Long storeId) {
        String storeCode = jdbc.sql("SELECT code FROM stores WHERE id = :id").param("id", storeId)
                .query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("El local " + storeId + " no existe"));
        String prefix = storeCode + "-" + type.code();
        if (prefix.length() > PREFIX_MAX_LENGTH) {
            prefix = "S" + storeId + "-" + type.code();
        }
        if (prefix.length() > PREFIX_MAX_LENGTH) {
            throw new IllegalStateException("No se puede construir un prefijo de a lo sumo "
                    + PREFIX_MAX_LENGTH + " caracteres para " + type + " del local " + storeId);
        }
        return prefix;
    }

    private record Row(long id, String prefix, long nextValue) {
    }
}
