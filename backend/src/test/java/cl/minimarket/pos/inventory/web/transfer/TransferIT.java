package cl.minimarket.pos.inventory.web.transfer;

import cl.minimarket.pos.inventory.StockApi;
import cl.minimarket.pos.inventory.StockCommand;
import cl.minimarket.pos.inventory.StockLine;
import cl.minimarket.pos.inventory.StockMovementType;
import cl.minimarket.pos.inventory.StockSourceType;
import cl.minimarket.pos.inventory.TransferDifferenceHandler;
import cl.minimarket.pos.inventory.TransferDifferenceHandler.TransferDifference;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del módulo de transferencias entre locales (T51, {@code HU-INV-07}).
 */
class TransferIT extends AbstractIntegrationIT {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired StockApi stockApi;
    @Autowired BusinessClock clock;
    @Autowired PlatformTransactionManager txManager;

    @MockitoSpyBean TransferDifferenceHandler differenceHandler;

    private TransactionTemplate tx;
    private String suffix;
    private long userBothStores;
    private long storeOrigin;
    private long storeDest;
    private long storeThird;
    private long categoryId;
    private long productA;
    private long productB;

    @BeforeEach
    void setUp() {
        reset(differenceHandler);
        tx = new TransactionTemplate(txManager);
        suffix = UUID.randomUUID().toString().substring(0, 8);
        userBothStores = TestData.insertUser(ownerJdbc());
        storeOrigin = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        storeDest = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        storeThird = TestData.insertStore(ownerJdbc(), TestData.uniqueStoreCode());
        assignStoreToUser(userBothStores, storeOrigin, true);
        assignStoreToUser(userBothStores, storeDest, false);
        assignStoreToUser(userBothStores, storeThird, false);

        categoryId = insertCategory("Abarrotes-" + suffix);
        productA = insertProduct("TR-A-" + suffix, "Arroz " + suffix, categoryId, new BigDecimal("700.0000"), false);
        productB = insertProduct("TR-B-" + suffix, "Fideos " + suffix, categoryId, new BigDecimal("500.0000"), false);

        insertStock(storeOrigin, productA, new BigDecimal("20.000"));
        insertStock(storeOrigin, productB, new BigDecimal("15.000"));
        insertStock(storeDest, productA, new BigDecimal("2.000"));
    }

    @Test
    void fullCycle_sendAndPartialReceive_updatesStockInTransitDifferenceHandlerAndAudit()
            throws Exception {
        // 1. Crear borrador desde local origen (10 unidades de productA)
        String createBody =
                """
                {
                  "destinationStoreId": %d,
                  "notes": "Reposición semanal",
                  "lines": [
                    { "productId": %d, "qtySent": 8 }
                  ]
                }
                """
                        .formatted(storeDest, productA);

        MvcResult createRes =
                mvc.perform(
                                post("/api/v1/stock-transfers")
                                        .header("X-Store-Id", storeOrigin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(createBody)
                                        .with(
                                                user(String.valueOf(userBothStores))
                                                        .authorities(
                                                                new SimpleGrantedAuthority(
                                                                        Permissions
                                                                                .INVENTORY_TRANSFER_SEND))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("DRAFT"))
                        .andExpect(jsonPath("$.origin.id").value(storeOrigin))
                        .andExpect(jsonPath("$.destination.id").value(storeDest))
                        .andReturn();

        JsonNode createdJson = jsonMapper.readTree(createRes.getResponse().getContentAsString());
        long transferId = createdJson.get("id").asLong();
        long version = createdJson.get("version").asLong();

        // 2. Editar borrador en origen aumentando cantidad a 10
        String updateBody =
                """
                {
                  "destinationStoreId": %d,
                  "notes": "Reposición ajustada",
                  "version": %d,
                  "lines": [
                    { "productId": %d, "qtySent": 10 }
                  ]
                }
                """
                        .formatted(storeDest, version, productA);

        MvcResult updateRes =
                mvc.perform(
                                put("/api/v1/stock-transfers/" + transferId)
                                        .header("X-Store-Id", storeOrigin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(updateBody)
                                        .with(
                                                user(String.valueOf(userBothStores))
                                                        .authorities(
                                                                new SimpleGrantedAuthority(
                                                                        Permissions
                                                                                .INVENTORY_TRANSFER_SEND))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.lines[0].qtySent").value(10.0))
                        .andReturn();

        long lineId =
                jsonMapper
                        .readTree(updateRes.getResponse().getContentAsString())
                        .get("lines")
                        .get(0)
                        .get("id")
                        .asLong();

        // 3. Despachar desde origen con idempotencia -> TRANSFER_OUT y en tránsito = 10
        String sendKey = UUID.randomUUID().toString();
        mvc.perform(
                        post("/api/v1/stock-transfers/" + transferId + "/send")
                                .header("X-Store-Id", storeOrigin)
                                .header("Idempotency-Key", sendKey)
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_SEND))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.sentBy.id").value(userBothStores));

        // Replay idempotente: no vuelve a descontar
        mvc.perform(
                        post("/api/v1/stock-transfers/" + transferId + "/send")
                                .header("X-Store-Id", storeOrigin)
                                .header("Idempotency-Key", sendKey)
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_SEND))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"));

        assertThat(stockQuantity(storeOrigin, productA)).isEqualByComparingTo("10.000");
        assertThat(stockQuantity(storeDest, productA)).isEqualByComparingTo("2.000");
        assertThat(movementCount(storeOrigin, productA, "TRANSFER_OUT", transferId)).isEqualTo(1);
        assertThat(stockApi.inTransitInbound(storeDest))
                .containsEntry(productA, new BigDecimal("10.000"));
        assertThat(stockApi.companyQuantity(productA)).isEqualByComparingTo("22.000");
        assertThat(auditCount("TRANSFER_SENT", transferId)).isEqualTo(1);

        // 4. Intentar recepcionar desde el local de origen -> 403 ACCESS_DENIED (HU-INV-07)
        String receiveOkBody =
                """
                {
                  "lines": [
                    { "lineId": %d, "qtyReceived": 9, "differenceReason": "1 unidad dañada en traslado" }
                  ]
                }
                """
                        .formatted(lineId);

        mvc.perform(
                        post("/api/v1/stock-transfers/" + transferId + "/receive")
                                .header("X-Store-Id", storeOrigin)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(receiveOkBody)
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_RECEIVE))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Tercer local no involucrado -> 404 NOT_FOUND
        mvc.perform(
                        get("/api/v1/stock-transfers/" + transferId)
                                .header("X-Store-Id", storeThird)
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_READ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 5. Recepcionar 9 sin differenceReason -> 400 VALIDATION_ERROR
        String missingReasonBody =
                """
                {
                  "lines": [
                    { "lineId": %d, "qtyReceived": 9 }
                  ]
                }
                """
                        .formatted(lineId);

        mvc.perform(
                        post("/api/v1/stock-transfers/" + transferId + "/receive")
                                .header("X-Store-Id", storeDest)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(missingReasonBody)
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_RECEIVE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 6. Recepcionar 9 con motivo -> TRANSFER_IN por 9, handler invocado una vez con 1, tránsito 0
        mvc.perform(
                        post("/api/v1/stock-transfers/" + transferId + "/receive")
                                .header("X-Store-Id", storeDest)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(receiveOkBody)
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_RECEIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.receivedBy.id").value(userBothStores))
                .andExpect(jsonPath("$.lines[0].qtyReceived").value(9.0))
                .andExpect(
                        jsonPath("$.lines[0].differenceReason")
                                .value("1 unidad dañada en traslado"));

        assertThat(stockQuantity(storeDest, productA)).isEqualByComparingTo("11.000");
        assertThat(movementCount(storeDest, productA, "TRANSFER_IN", transferId)).isEqualTo(1);
        assertThat(stockApi.inTransitInbound(storeDest)).isEmpty();
        assertThat(stockApi.companyQuantity(productA)).isEqualByComparingTo("21.000");
        assertThat(auditCount("TRANSFER_RECEIVED", transferId)).isEqualTo(1);

        ArgumentCaptor<TransferDifference> diffCaptor =
                ArgumentCaptor.forClass(TransferDifference.class);
        verify(differenceHandler, times(1)).handle(diffCaptor.capture());
        TransferDifference diff = diffCaptor.getValue();
        assertThat(diff.transferId()).isEqualTo(transferId);
        assertThat(diff.originStoreId()).isEqualTo(storeOrigin);
        assertThat(diff.destinationStoreId()).isEqualTo(storeDest);
        assertThat(diff.productId()).isEqualTo(productA);
        assertThat(diff.differenceQuantity()).isEqualByComparingTo("1.000");
        assertThat(diff.reason()).isEqualTo("1 unidad dañada en traslado");
    }

    @Test
    void listAndFilters_cancelDraft_andPermissionChecks() throws Exception {
        // Sin permiso inventory:transfer:send -> 403 ACCESS_DENIED
        String createBody =
                """
                {
                  "destinationStoreId": %d,
                  "lines": [
                    { "productId": %d, "qtySent": 3 }
                  ]
                }
                """
                        .formatted(storeDest, productB);

        mvc.perform(
                        post("/api/v1/stock-transfers")
                                .header("X-Store-Id", storeOrigin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody)
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Crear borrador y cancelar desde origen
        MvcResult createRes =
                mvc.perform(
                                post("/api/v1/stock-transfers")
                                        .header("X-Store-Id", storeOrigin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(createBody)
                                        .with(
                                                user(String.valueOf(userBothStores))
                                                        .authorities(
                                                                new SimpleGrantedAuthority(
                                                                        Permissions
                                                                                .INVENTORY_TRANSFER_SEND))))
                        .andExpect(status().isCreated())
                        .andReturn();
        long draftId =
                jsonMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(
                        post("/api/v1/stock-transfers/" + draftId + "/cancel")
                                .header("X-Store-Id", storeOrigin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\": \"Solicitud duplicada\"}")
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_CANCEL))))
                .andExpect(status().isNoContent());

        // No se puede despachar una transferencia cancelada -> 409 INVALID_STATE_TRANSITION
        mvc.perform(
                        post("/api/v1/stock-transfers/" + draftId + "/send")
                                .header("X-Store-Id", storeOrigin)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_SEND))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // Filtros direction=out (origen) y direction=in (destino)
        String today = clock.businessDate().toString();
        mvc.perform(
                        get("/api/v1/stock-transfers")
                                .header("X-Store-Id", storeOrigin)
                                .param("direction", "out")
                                .param("status", "CANCELLED")
                                .param("from", today)
                                .param("to", today)
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(draftId));

        mvc.perform(
                        get("/api/v1/stock-transfers")
                                .header("X-Store-Id", storeDest)
                                .param("direction", "in")
                                .param("status", "CANCELLED")
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(draftId));

        mvc.perform(
                        get("/api/v1/stock-transfers")
                                .header("X-Store-Id", storeDest)
                                .param("direction", "out")
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        assertThat(stockApi.inTransitInbound(storeDest)).isEmpty();
        assertThat(stockApi.companyQuantity(productB)).isEqualByComparingTo("15.000");
        verify(differenceHandler, times(0)).handle(any());
    }

    @Test
    void exactReceive_withoutDifference_conservesCompanyQuantityAndSkipsDifferenceHandler()
            throws Exception {
        insertStock(storeDest, productB, new BigDecimal("0.000"));

        String createBody =
                """
                {
                  "destinationStoreId": %d,
                  "lines": [
                    { "productId": %d, "qtySent": 5 }
                  ]
                }
                """
                        .formatted(storeDest, productB);

        MvcResult createRes =
                mvc.perform(
                                post("/api/v1/stock-transfers")
                                        .header("X-Store-Id", storeOrigin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(createBody)
                                        .with(
                                                user(String.valueOf(userBothStores))
                                                        .authorities(
                                                                new SimpleGrantedAuthority(
                                                                        Permissions
                                                                                .INVENTORY_TRANSFER_SEND))))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode createdJson = jsonMapper.readTree(createRes.getResponse().getContentAsString());
        long transferId = createdJson.get("id").asLong();
        long lineId = createdJson.get("lines").get(0).get("id").asLong();

        // En DRAFT no figura en tránsito
        assertThat(stockApi.inTransitInbound(storeDest)).isEmpty();
        assertThat(stockApi.companyQuantity(productB)).isEqualByComparingTo("15.000");

        mvc.perform(
                        post("/api/v1/stock-transfers/" + transferId + "/send")
                                .header("X-Store-Id", storeOrigin)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_SEND))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"));

        // En tránsito (SENT): 10 en origen + 0 en destino + 5 en tránsito = 15 en empresa
        assertThat(stockApi.inTransitInbound(storeDest))
                .containsEntry(productB, new BigDecimal("5.000"));
        assertThat(stockApi.companyQuantity(productB)).isEqualByComparingTo("15.000");

        String receiveExactBody =
                """
                {
                  "lines": [
                    { "lineId": %d, "qtyReceived": 5 }
                  ]
                }
                """
                        .formatted(lineId);

        mvc.perform(
                        post("/api/v1/stock-transfers/" + transferId + "/receive")
                                .header("X-Store-Id", storeDest)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(receiveExactBody)
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_RECEIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.lines[0].qtyReceived").value(5.0));

        assertThat(stockQuantity(storeOrigin, productB)).isEqualByComparingTo("10.000");
        assertThat(stockQuantity(storeDest, productB)).isEqualByComparingTo("5.000");
        assertThat(stockApi.inTransitInbound(storeDest)).isEmpty();
        assertThat(stockApi.companyQuantity(productB)).isEqualByComparingTo("15.000");
        verify(differenceHandler, times(0)).handle(any());
    }

    @Test
    void lotControlledFefo_splitsMultipleLotsOnSend_preservesLotsOnReceive_andReportsLotDifference()
            throws Exception {
        long lotProduct =
                insertProduct(
                        "TR-LOT-" + suffix,
                        "Yogurt " + suffix,
                        categoryId,
                        new BigDecimal("600.0000"),
                        true);
        insertStock(storeOrigin, lotProduct, BigDecimal.ZERO);
        insertStock(storeDest, lotProduct, BigDecimal.ZERO);

        LocalDate exp1 = clock.businessDate().plusDays(5);
        LocalDate exp2 = clock.businessDate().plusDays(20);
        long originLot1Id =
                receiveStockLot(storeOrigin, lotProduct, "LOT-F1-" + suffix, exp1, new BigDecimal("4.000"));
        long originLot2Id =
                receiveStockLot(storeOrigin, lotProduct, "LOT-F2-" + suffix, exp2, new BigDecimal("6.000"));

        // 1. Crear borrador sin originLotId (FEFO automático) por 8 unidades (> saldo del lote 1)
        String createBody =
                """
                {
                  "destinationStoreId": %d,
                  "notes": "Traslado refrigerado FEFO",
                  "lines": [
                    { "productId": %d, "qtySent": 8 }
                  ]
                }
                """
                        .formatted(storeDest, lotProduct);

        MvcResult createRes =
                mvc.perform(
                                post("/api/v1/stock-transfers")
                                        .header("X-Store-Id", storeOrigin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(createBody)
                                        .with(
                                                user(String.valueOf(userBothStores))
                                                        .authorities(
                                                                new SimpleGrantedAuthority(
                                                                        Permissions
                                                                                .INVENTORY_TRANSFER_SEND))))
                        .andExpect(status().isCreated())
                        .andReturn();

        long transferId =
                jsonMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // 2. Enviar desde origen -> desdobla la línea en 2 lotes según FEFO (4 + 4)
        MvcResult sendRes =
                mvc.perform(
                                post("/api/v1/stock-transfers/" + transferId + "/send")
                                        .header("X-Store-Id", storeOrigin)
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .with(
                                                user(String.valueOf(userBothStores))
                                                        .authorities(
                                                                new SimpleGrantedAuthority(
                                                                        Permissions
                                                                                .INVENTORY_TRANSFER_SEND))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("SENT"))
                        .andExpect(jsonPath("$.lines.length()").value(2))
                        .andExpect(jsonPath("$.lines[0].lotNumber").value("LOT-F1-" + suffix))
                        .andExpect(jsonPath("$.lines[0].expiryDate").value(exp1.toString()))
                        .andExpect(jsonPath("$.lines[0].qtySent").value(4.0))
                        .andExpect(jsonPath("$.lines[1].lotNumber").value("LOT-F2-" + suffix))
                        .andExpect(jsonPath("$.lines[1].expiryDate").value(exp2.toString()))
                        .andExpect(jsonPath("$.lines[1].qtySent").value(4.0))
                        .andReturn();

        JsonNode sentJson = jsonMapper.readTree(sendRes.getResponse().getContentAsString());
        long line1Id = sentJson.get("lines").get(0).get("id").asLong();
        long line2Id = sentJson.get("lines").get(1).get("id").asLong();

        assertThat(stockQuantity(storeOrigin, lotProduct)).isEqualByComparingTo("2.000");
        assertThat(lotQuantity(originLot1Id)).isEqualByComparingTo("0.000");
        assertThat(lotQuantity(originLot2Id)).isEqualByComparingTo("2.000");
        assertThat(stockApi.inTransitInbound(storeDest))
                .containsEntry(lotProduct, new BigDecimal("8.000"));
        assertThat(stockApi.companyQuantity(lotProduct)).isEqualByComparingTo("10.000");

        // 3. Recepcionar en destino: lote 1 completo (4), lote 2 con 1 faltante (3)
        String receiveBody =
                """
                {
                  "lines": [
                    { "lineId": %d, "qtyReceived": 4 },
                    { "lineId": %d, "qtyReceived": 3, "differenceReason": "1 unidad rota en lote 2" }
                  ]
                }
                """
                        .formatted(line1Id, line2Id);

        mvc.perform(
                        post("/api/v1/stock-transfers/" + transferId + "/receive")
                                .header("X-Store-Id", storeDest)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(receiveBody)
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_RECEIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].qtyReceived").value(4.0))
                .andExpect(jsonPath("$.lines[1].qtyReceived").value(3.0));

        assertThat(stockQuantity(storeDest, lotProduct)).isEqualByComparingTo("7.000");
        assertThat(stockApi.inTransitInbound(storeDest)).isEmpty();
        assertThat(stockApi.companyQuantity(lotProduct)).isEqualByComparingTo("9.000");

        long destLot1Id = findLotId(storeDest, lotProduct, "LOT-F1-" + suffix);
        long destLot2Id = findLotId(storeDest, lotProduct, "LOT-F2-" + suffix);
        assertThat(lotQuantity(destLot1Id)).isEqualByComparingTo("4.000");
        assertThat(lotExpiry(destLot1Id)).isEqualTo(exp1);
        assertThat(lotQuantity(destLot2Id)).isEqualByComparingTo("3.000");
        assertThat(lotExpiry(destLot2Id)).isEqualTo(exp2);

        ArgumentCaptor<TransferDifference> diffCaptor =
                ArgumentCaptor.forClass(TransferDifference.class);
        verify(differenceHandler, times(1)).handle(diffCaptor.capture());
        TransferDifference diff = diffCaptor.getValue();
        assertThat(diff.transferLineId()).isEqualTo(line2Id);
        assertThat(diff.originLotId()).isEqualTo(originLot2Id);
        assertThat(diff.differenceQuantity()).isEqualByComparingTo("1.000");
        assertThat(diff.reason()).isEqualTo("1 unidad rota en lote 2");
    }

    @Test
    void receive_idempotentReplay_andMultiSort_doNotDuplicateEffects() throws Exception {
        String createBody =
                """
                {
                  "destinationStoreId": %d,
                  "lines": [
                    { "productId": %d, "qtySent": 6 }
                  ]
                }
                """
                        .formatted(storeDest, productA);

        MvcResult createRes =
                mvc.perform(
                                post("/api/v1/stock-transfers")
                                        .header("X-Store-Id", storeOrigin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(createBody)
                                        .with(
                                                user(String.valueOf(userBothStores))
                                                        .authorities(
                                                                new SimpleGrantedAuthority(
                                                                        Permissions
                                                                                .INVENTORY_TRANSFER_SEND))))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode created = jsonMapper.readTree(createRes.getResponse().getContentAsString());
        long transferId = created.get("id").asLong();
        long lineId = created.get("lines").get(0).get("id").asLong();

        mvc.perform(
                        post("/api/v1/stock-transfers/" + transferId + "/send")
                                .header("X-Store-Id", storeOrigin)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_SEND))))
                .andExpect(status().isOk());

        String receiveKey = UUID.randomUUID().toString();
        String receiveBody =
                """
                {
                  "lines": [
                    { "lineId": %d, "qtyReceived": 5, "differenceReason": "Faltante en conteo" }
                  ]
                }
                """
                        .formatted(lineId);

        mvc.perform(
                        post("/api/v1/stock-transfers/" + transferId + "/receive")
                                .header("X-Store-Id", storeDest)
                                .header("Idempotency-Key", receiveKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(receiveBody)
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_RECEIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.lines[0].qtyReceived").value(5.0));

        // Replay idempotente con misma clave y cuerpo: retorna 200 sin duplicar movimientos ni merma
        mvc.perform(
                        post("/api/v1/stock-transfers/" + transferId + "/receive")
                                .header("X-Store-Id", storeDest)
                                .header("Idempotency-Key", receiveKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(receiveBody)
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_RECEIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.lines[0].qtyReceived").value(5.0));

        assertThat(stockQuantity(storeDest, productA)).isEqualByComparingTo("7.000");
        assertThat(movementCount(storeDest, productA, "TRANSFER_IN", transferId)).isEqualTo(1);
        assertThat(auditCount("TRANSFER_RECEIVED", transferId)).isEqualTo(1);
        verify(differenceHandler, times(1)).handle(any());

        // Multi-criterio sort con comas (?sort=number,asc&sort=id,desc)
        mvc.perform(
                        get("/api/v1/stock-transfers")
                                .header("X-Store-Id", storeDest)
                                .param("direction", "in")
                                .param("sort", "number,asc")
                                .param("sort", "id,desc")
                                .with(
                                        user(String.valueOf(userBothStores))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                Permissions
                                                                        .INVENTORY_TRANSFER_READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(transferId));
    }

    private void assignStoreToUser(long uid, long sid, boolean isDefault) {
        ownerJdbc()
                .sql(
                        """
                        INSERT INTO user_stores (user_id, store_id, is_default)
                        VALUES (:userId, :storeId, :isDefault)
                        ON CONFLICT (user_id, store_id) DO NOTHING
                        """)
                .param("userId", uid)
                .param("storeId", sid)
                .param("isDefault", isDefault)
                .update();
    }

    private long insertCategory(String name) {
        return ownerJdbc()
                .sql(
                        """
                        INSERT INTO categories (name, active, created_at, updated_at, version)
                        VALUES (:name, true, now(), now(), 0)
                        RETURNING id
                        """)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertProduct(
            String sku, String name, long catId, BigDecimal avgCost, boolean lotControlled) {
        return ownerJdbc()
                .sql(
                        """
                        INSERT INTO products (sku, name, category_id, sale_type, unit_of_measure,
                                              price_gross, avg_cost, vat_exempt, restricted_type,
                                              lot_controlled, active, created_at, updated_at, version)
                        VALUES (:sku, :name, :categoryId, 'UNIT', 'UNIT',
                                1500, :avgCost, false, NULL,
                                :lotControlled, true, now(), now(), 0)
                        RETURNING id
                        """)
                .param("sku", sku)
                .param("name", name)
                .param("categoryId", catId)
                .param("avgCost", avgCost)
                .param("lotControlled", lotControlled)
                .query(Long.class)
                .single();
    }

    private long receiveStockLot(
            long sid, long pid, String lotNumber, LocalDate expiryDate, BigDecimal qty) {
        return Objects.requireNonNull(
                tx.execute(
                        status ->
                                stockApi.apply(
                                                new StockCommand(
                                                        sid,
                                                        StockMovementType.PURCHASE_RECEIPT,
                                                        StockSourceType.GOODS_RECEIPT,
                                                        1L,
                                                        userBothStores,
                                                        "Ingreso lote prueba",
                                                        java.util.List.of(
                                                                new StockLine(
                                                                        pid,
                                                                        qty,
                                                                        new BigDecimal("600.0000"),
                                                                        null,
                                                                        null,
                                                                        lotNumber,
                                                                        expiryDate))))
                                        .getFirst()
                                        .allocations()
                                        .getFirst()
                                        .lotId()));
    }

    private long findLotId(long sid, long pid, String lotNumber) {
        return ownerJdbc()
                .sql(
                        """
                        SELECT id
                          FROM lots
                         WHERE store_id = :sid
                           AND product_id = :pid
                           AND lot_number = :lotNumber
                        """)
                .param("sid", sid)
                .param("pid", pid)
                .param("lotNumber", lotNumber)
                .query(Long.class)
                .single();
    }

    private BigDecimal lotQuantity(long lotId) {
        return ownerJdbc()
                .sql("SELECT quantity FROM lots WHERE id = :lotId")
                .param("lotId", lotId)
                .query(BigDecimal.class)
                .single();
    }

    private LocalDate lotExpiry(long lotId) {
        return ownerJdbc()
                .sql("SELECT expiry_date FROM lots WHERE id = :lotId")
                .param("lotId", lotId)
                .query((rs, rowNum) -> rs.getObject("expiry_date", LocalDate.class))
                .single();
    }

    private void insertStock(long sid, long pid, BigDecimal qty) {
        ownerJdbc()
                .sql(
                        """
                        INSERT INTO stock (store_id, product_id, quantity, min_qty, max_qty)
                        VALUES (:storeId, :productId, :qty, 0, 100)
                        """)
                .param("storeId", sid)
                .param("productId", pid)
                .param("qty", qty)
                .update();
    }

    private BigDecimal stockQuantity(long sid, long pid) {
        return ownerJdbc()
                .sql("SELECT quantity FROM stock WHERE store_id = :sid AND product_id = :pid")
                .param("sid", sid)
                .param("pid", pid)
                .query(BigDecimal.class)
                .single();
    }

    private long movementCount(long sid, long pid, String movementType, long sourceId) {
        return ownerJdbc()
                .sql(
                        """
                        SELECT COUNT(*)
                          FROM stock_movements
                         WHERE store_id = :sid
                           AND product_id = :pid
                           AND movement_type = :type
                           AND source_type = 'STOCK_TRANSFER'
                           AND source_id = :sourceId
                        """)
                .param("sid", sid)
                .param("pid", pid)
                .param("type", movementType)
                .param("sourceId", sourceId)
                .query(Long.class)
                .single();
    }

    private long auditCount(String action, long transferId) {
        return ownerJdbc()
                .sql(
                        """
                        SELECT COUNT(*)
                          FROM audit_log
                         WHERE action = :action
                           AND entity_type = 'stock_transfer'
                           AND entity_id = :entityId
                        """)
                .param("action", action)
                .param("entityId", String.valueOf(transferId))
                .query(Long.class)
                .single();
    }
}
