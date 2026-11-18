package cl.minimarket.pos.shared.sequence;

import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link SequenceService} contra PostgreSQL real: formato, alcance, rollback y concurrencia (RN-29). */
class SequenceServiceIT extends AbstractIntegrationIT {

    @Autowired
    SequenceService sequences;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    @Test
    void generatesConsecutiveFormattedFoliosPerStore() {
        String code = TestData.uniqueStoreCode();
        long store = TestData.insertStore(jdbc, code);
        long otherStore = TestData.insertStore(jdbc, TestData.uniqueStoreCode());

        List<String> sales = inTransaction(() -> List.of(
                sequences.next(SequenceType.SALE, store),
                sequences.next(SequenceType.SALE, store),
                sequences.next(SequenceType.SALE, store)));

        assertThat(sales).containsExactly(code + "-V-00000001", code + "-V-00000002", code + "-V-00000003");
        // Otro tipo y otro local llevan su propio correlativo.
        assertThat(inTransaction(() -> sequences.next(SequenceType.SALE_RETURN, store))).isEqualTo(code + "-D-00000001");
        assertThat(inTransaction(() -> sequences.next(SequenceType.SALE, otherStore))).endsWith("-V-00000001");
        assertThat(jdbc.sql("SELECT next_value FROM document_sequences WHERE sequence_type = 'SALE' AND store_id = :s")
                .param("s", store).query(Long.class).single()).isEqualTo(4L);
    }

    @Test
    void globalSequenceHasNoStoreAndIsContinuous() {
        jdbc.sql("DELETE FROM document_sequences WHERE sequence_type = 'FOLIO_CREDIT_NOTE'").update();

        assertThat(inTransaction(() -> sequences.next(SequenceType.FOLIO_CREDIT_NOTE, null))).isEqualTo("NC-00000001");
        assertThat(inTransaction(() -> sequences.next(SequenceType.FOLIO_CREDIT_NOTE, null))).isEqualTo("NC-00000002");
    }

    @Test
    void existingRowKeepsItsConfiguredPrefixAndValue() {
        long store = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        jdbc.sql("INSERT INTO document_sequences (sequence_type, store_id, prefix, next_value) VALUES ('TRANSFER', :s, 'TRF-X', 1234)")
                .param("s", store).update();

        assertThat(inTransaction(() -> sequences.next(SequenceType.TRANSFER, store))).isEqualTo("TRF-X-00001234");
    }

    @Test
    void longStoreCodeFallsBackToStoreIdPrefix() {
        long store = TestData.insertStore(jdbc, "Z" + TestData.uniqueStoreCode().substring(1) + "99"); // 10 caracteres

        assertThat(inTransaction(() -> sequences.next(SequenceType.PURCHASE_ORDER, store)))
                .isEqualTo("S" + store + "-OC-00000001");
    }

    @Test
    void rolledBackTransactionDoesNotConsumeTheFolio() {
        long store = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        assertThat(inTransaction(() -> sequences.next(SequenceType.SALE, store))).endsWith("-00000001");

        assertThatThrownBy(() -> inTransaction(() -> {
            sequences.next(SequenceType.SALE, store);
            throw new IllegalStateException("la venta falla después de pedir el folio");
        })).isInstanceOf(IllegalStateException.class);

        // Sin huecos: el folio 2 sigue disponible.
        assertThat(inTransaction(() -> sequences.next(SequenceType.SALE, store))).endsWith("-00000002");
    }

    @Test
    void requiresAnActiveTransaction() {
        long store = TestData.insertStore(jdbc, TestData.uniqueStoreCode());

        assertThatThrownBy(() -> sequences.next(SequenceType.SALE, store))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void rejectsStoreIdThatDoesNotMatchTheScope() {
        assertThatThrownBy(() -> inTransaction(() -> sequences.next(SequenceType.SALE, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inTransaction(() -> sequences.next(SequenceType.FOLIO_BOLETA, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownStoreIsRejectedWhenCreatingTheSequence() {
        assertThatThrownBy(() -> inTransaction(() -> sequences.next(SequenceType.SALE, 987_654_321L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentCallersGetUniqueGaplessFolios() throws Exception {
        int threads = 20;
        int perThread = 10;
        long store = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    List<String> mine = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        mine.add(inTransaction(() -> sequences.next(SequenceType.SALE, store)));
                    }
                    return mine;
                }));
            }
            start.countDown();
            List<String> all = new ArrayList<>();
            for (Future<List<String>> future : futures) {
                all.addAll(future.get(60, TimeUnit.SECONDS));
            }

            Set<String> unique = new TreeSet<>(all);
            assertThat(all).hasSize(threads * perThread);
            assertThat(unique).as("sin duplicados").hasSize(threads * perThread);
            // Sin huecos: exactamente 1..200.
            Set<Long> numbers = new TreeSet<>();
            all.forEach(folio -> numbers.add(Long.parseLong(folio.substring(folio.lastIndexOf('-') + 1))));
            assertThat(numbers.iterator().next()).isEqualTo(1L);
            assertThat(((TreeSet<Long>) numbers).last()).isEqualTo((long) threads * perThread);
            assertThat(jdbc.sql("SELECT next_value FROM document_sequences WHERE sequence_type = 'SALE' AND store_id = :s")
                    .param("s", store).query(Long.class).single()).isEqualTo(threads * perThread + 1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentFirstUseCreatesTheSequenceOnlyOnce() throws Exception {
        int threads = 10;
        long store = TestData.insertStore(jdbc, TestData.uniqueStoreCode());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return inTransaction(() -> sequences.next(SequenceType.SHRINKAGE, store));
                }));
            }
            start.countDown();
            Set<String> folios = new TreeSet<>();
            for (Future<String> future : futures) {
                folios.add(future.get(60, TimeUnit.SECONDS));
            }

            assertThat(folios).hasSize(threads);
            assertThat(jdbc.sql("SELECT count(*) FROM document_sequences WHERE sequence_type = 'SHRINKAGE' AND store_id = :s")
                    .param("s", store).query(Long.class).single()).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }
}
