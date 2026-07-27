package de.palsoftware.yvoke.chat.core.service;

import de.palsoftware.yvoke.chat.core.model.ModelPricing;
import de.palsoftware.yvoke.chat.core.repository.CostQueryRepository;
import de.palsoftware.yvoke.chat.core.repository.ModelPricingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CostCalculationService {

    private static final Logger log = LoggerFactory.getLogger(CostCalculationService.class);

    private final ModelPricingRepository pricingRepository;
    private final CostQueryRepository costQueryRepository;
    /**
     * Hard cap on rows fetched by the per-call/per-message cost-explorer views (PRF-01). Without
     * it, a wide time range ({@code timePreset='all'}) materializes the whole {@code llm_call_logs}
     * table into heap and Thymeleaf. Full keyset pagination is deferred to the
     * CostCalculationService split (Wave 3.2); this cap makes the current view safe in the
     * meantime.
     */
    private final int explorerRowCap;

    public CostCalculationService(ModelPricingRepository pricingRepository,
        CostQueryRepository costQueryRepository,
        @Value("${app.chat.cost-explorer.max-rows}") int explorerRowCap) {
        this.pricingRepository = pricingRepository;
        this.costQueryRepository = costQueryRepository;
        this.explorerRowCap = explorerRowCap;
    }

    public record ModelCostBreakdown(String modelName, long promptTokens, long completionTokens,
        long cachedTokens, long thoughtTokens, BigDecimal estimatedCostUsd) {}

    public record CostReport(List<ModelCostBreakdown> breakdowns, long totalPromptTokens,
        long totalCompletionTokens, long totalCachedTokens, long totalThoughtTokens,
        BigDecimal estimatedCostUsd) {}

    public record UserCostRow(UUID userId, String displayName, String email, long totalTokens,
        BigDecimal estimatedCostUsd) {}

    public record ConversationCostRow(UUID conversationId, String title, String userName,
        long totalTokens, BigDecimal estimatedCostUsd) {}

    public record MasProfileCostRow(String profileName, long totalTokens,
        BigDecimal estimatedCostUsd) {}

    public record MessageCostRow(UUID messageId, String role, String model, long promptTokens,
        long completionTokens, long cachedTokens, long thoughtTokens, BigDecimal estimatedCostUsd,
        Instant createdAt) {}

    /**
     * The cost-dashboard overview fragment's four pieces, computed against a single pricing
     * snapshot (PRF-14). Accessor names mirror the individual model attributes the controller used
     * to bind so the template is unchanged.
     */
    public record OverviewReport(CostReport report, List<UserCostRow> topUsers,
        List<ConversationCostRow> topConversations, List<MasProfileCostRow> masProfiles) {}

    public record FilteredConversationRow(
        UUID conversationId,
        String title,
        String userName,
        String modelsUsed,
        String masProfilesUsed,
        String playbooksUsed,
        long messageCount,
        long agentStepCount,
        long totalPromptTokens,
        long totalCompletionTokens,
        long totalCachedTokens,
        long totalThoughtTokens,
        long totalTokens,
        BigDecimal estimatedCostUsd,
        Instant lastActivityAt
    ) {
        public BigDecimal averageCostPerMessageUsd() {
            if (messageCount <= 0 || estimatedCostUsd == null) return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
            return estimatedCostUsd.divide(BigDecimal.valueOf(messageCount), 6, RoundingMode.HALF_UP);
        }
    }

    public record FilteredMessageRow(
        UUID id,
        String itemType,
        String role,
        String playbook,
        String masProfile,
        String model,
        UUID conversationId,
        String conversationTitle,
        String userName,
        long promptTokens,
        long completionTokens,
        long cachedTokens,
        long thoughtTokens,
        long totalTokens,
        BigDecimal estimatedCostUsd,
        Instant createdAt,
        long callCount,
        UUID messageId
    ) {
        public FilteredMessageRow(
            UUID id, String itemType, String role, String playbook, String masProfile, String model,
            UUID conversationId, String conversationTitle, String userName,
            long promptTokens, long completionTokens, long cachedTokens, long thoughtTokens,
            long totalTokens, BigDecimal estimatedCostUsd, Instant createdAt) {
            this(id, itemType, role, playbook, masProfile, model, conversationId, conversationTitle, userName,
                promptTokens, completionTokens, cachedTokens, thoughtTokens, totalTokens, estimatedCostUsd, createdAt, 1L, id);
        }

        public BigDecimal averageCostPerCallUsd() {
            if (callCount <= 0 || estimatedCostUsd == null) return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
            return estimatedCostUsd.divide(BigDecimal.valueOf(callCount), 6, RoundingMode.HALF_UP);
        }
    }


    /** Name of the gateway status meaning the provider was never called and billed nothing. */
    private static final String GATEWAY_REPLAYED = "REPLAYED";

    private static boolean isReplayed(Map<String, Object> row) {
        return GATEWAY_REPLAYED.equals(row.get("gateway_cache_status"));
    }

    private static boolean isGatewayRouted(Map<String, Object> row) {
        return row.get("gateway_cache_status") != null;
    }

    public record FilteredCostExplorerReport(
        List<FilteredConversationRow> conversations,
        List<FilteredMessageRow> messages,
        String viewLevel,
        long totalPromptTokens,
        long totalCompletionTokens,
        long totalCachedTokens,
        long totalThoughtTokens,
        long totalTokens,
        BigDecimal totalCostUsd,
        int totalCount,
        String nextCursor,
        long replayedCalls,
        long forwardedCalls,
        BigDecimal costAvoidedUsd
    ) {

        /**
         * Back-compat for callers that predate gateway accounting; reports no gateway activity.
         */
        public FilteredCostExplorerReport(List<FilteredConversationRow> conversations,
            List<FilteredMessageRow> messages, String viewLevel, long totalPromptTokens,
            long totalCompletionTokens, long totalCachedTokens, long totalThoughtTokens,
            long totalTokens, BigDecimal totalCostUsd, int totalCount, String nextCursor) {
            this(conversations, messages, viewLevel, totalPromptTokens, totalCompletionTokens,
                totalCachedTokens, totalThoughtTokens, totalTokens, totalCostUsd, totalCount,
                nextCursor, 0L, 0L, BigDecimal.ZERO);
        }

        /** Share of gateway-routed calls served from cache, 0 when none were routed. */
        public BigDecimal cacheHitRatePercent() {
            long routed = replayedCalls + forwardedCalls;
            return routed == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(replayedCalls).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(routed), 1, RoundingMode.HALF_UP);
        }

        /** True when any call in this slice traversed an AI gateway. */
        public boolean hasGatewayActivity() {
            return replayedCalls + forwardedCalls > 0;
        }

        /** Back-compat: MESSAGE/CONVERSATION views are unpaged, so they carry no next cursor. */
        public FilteredCostExplorerReport(List<FilteredConversationRow> conversations,
            List<FilteredMessageRow> messages, String viewLevel, long totalPromptTokens,
            long totalCompletionTokens, long totalCachedTokens, long totalThoughtTokens,
            long totalTokens, BigDecimal totalCostUsd, int totalCount) {
            this(conversations, messages, viewLevel, totalPromptTokens, totalCompletionTokens,
                totalCachedTokens, totalThoughtTokens, totalTokens, totalCostUsd, totalCount, null);
        }

        public BigDecimal averageCostUsd() {
            if (totalCount <= 0 || totalCostUsd == null) return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
            return totalCostUsd.divide(BigDecimal.valueOf(totalCount), 6, RoundingMode.HALF_UP);
        }
    }

    // Global Overall
    public CostReport calculateGlobalCost(LocalDate startDate, LocalDate endDate) {
        return aggregateModelRows(costQueryRepository.globalModelTokenRows(startDate, endDate));
    }

    // User (userId)
    public CostReport calculateCostByUser(UUID userId, LocalDate startDate, LocalDate endDate) {
        return aggregateModelRows(
            costQueryRepository.userModelTokenRows(userId, startDate, endDate));
    }

    // Conversation (conversationId)
    public CostReport calculateCostByConversation(UUID conversationId, LocalDate startDate,
        LocalDate endDate) {
        return aggregateModelRows(
            costQueryRepository.conversationModelTokenRows(conversationId, startDate, endDate));
    }

    // MAS Run
    public CostReport calculateCostByAgentRun(UUID agentRunId) {
        return aggregateModelRows(costQueryRepository.agentRunModelTokenRows(agentRunId));
    }

    // MAS Profile (profileName)
    public CostReport calculateCostByMasProfile(String profileName, LocalDate startDate,
        LocalDate endDate) {
        return aggregateModelRows(
            costQueryRepository.masProfileModelTokenRows(profileName, startDate, endDate));
    }

    public List<Map<String, Object>> getAvailableUsers() {
        return costQueryRepository.availableUsers();
    }

    public List<Map<String, Object>> getAvailableConversations() {
        return costQueryRepository.availableConversations();
    }

    public List<String> getAvailableMasProfiles() {
        return costQueryRepository.availableMasProfiles();
    }

    public List<String> getAvailableModels() {
        return costQueryRepository.availableModels();
    }

    public List<String> getAvailableSources() {
        return costQueryRepository.availableSources();
    }

    public List<String> getAvailablePlaybooks() {
        return costQueryRepository.availablePlaybooks();
    }

    public List<MessageCostRow> getMessagesByConversation(UUID conversationId) {
        Map<String, ModelPricing> dbPrices = loadPricingMap();

        List<Map<String, Object>> rows = costQueryRepository.messageCostRows(conversationId);

        List<MessageCostRow> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            String role = (String) row.get("role");
            String model = (String) row.get("effective_model");
            long p = getLong(row.get("prompt_tokens"));
            long c = getLong(row.get("completion_tokens"));
            long ca = getLong(row.get("cached_tokens"));
            long t = getLong(row.get("thought_tokens"));
            Timestamp ts = (Timestamp) row.get("created_at");
            Instant createdAt = ts != null ? ts.toInstant() : null;

            BigDecimal cost = isReplayed(row) ? BigDecimal.ZERO
                : getModelCost(model != null ? model : "", p, c, ca, t, dbPrices);

            result.add(new MessageCostRow(id, role, model, p, c, ca, t,
                cost.setScale(6, RoundingMode.HALF_UP), createdAt));
        }
        return result;
    }

    public record UsedModelPricingStatus(String modelName, boolean hasPricing,
        BigDecimal promptPricePerMillion, BigDecimal completionPricePerMillion,
        BigDecimal cachedPricePerMillion, BigDecimal thoughtPricePerMillion, Instant updatedAt,
        long usageCount) {}

    public List<ModelPricing> getAllModelPricing() {
        return (List<ModelPricing>) pricingRepository.findAll();
    }

    public List<UsedModelPricingStatus> getAllUsedModelsWithPricingStatus() {
        Map<String, ModelPricing> dbPrices = loadPricingMap();

        Map<String, Long> usageCounts = new HashMap<>();
        List<Map<String, Object>> msgCounts = costQueryRepository.modelUsageCounts();
        for (Map<String, Object> r : msgCounts) {
            String m = (String) r.get("model");
            long cnt = getLong(r.get("cnt"));
            if (m != null && !m.isBlank()) {
                usageCounts.put(m, cnt);
            }
        }

        Set<String> allModelNames = new TreeSet<>(dbPrices.keySet());
        allModelNames.addAll(usageCounts.keySet());

        List<UsedModelPricingStatus> result = new ArrayList<>();
        for (String m : allModelNames) {
            ModelPricing mp = dbPrices.get(m);
            boolean hasPricing = (mp != null);
            long usage = usageCounts.getOrDefault(m, 0L);

            BigDecimal prompt = hasPricing ? mp.promptPricePerMillion() : null;
            BigDecimal completion = hasPricing ? mp.completionPricePerMillion() : null;
            BigDecimal cached = hasPricing ? mp.cachedPricePerMillion() : null;
            BigDecimal thought = hasPricing ? mp.thoughtPricePerMillion() : null;
            Instant updatedAt = hasPricing ? mp.updatedAt() : null;

            result.add(new UsedModelPricingStatus(m, hasPricing, prompt, completion, cached,
                thought, updatedAt, usage));
        }

        result.sort((a, b) -> {
            if (a.hasPricing() != b.hasPricing()) {
                return a.hasPricing() ? 1 : -1;
            }
            return a.modelName().compareToIgnoreCase(b.modelName());
        });

        return result;
    }

    @Transactional
    public void updateModelPricing(String modelName, BigDecimal prompt, BigDecimal completion,
        BigDecimal cached, BigDecimal thought) {
        ModelPricing existing = pricingRepository.findByModelName(modelName).orElse(null);
        if (existing != null) {
            pricingRepository.upsert(new ModelPricing(existing.id(), modelName, prompt, completion,
                cached, thought, Instant.now()));
        } else {
            pricingRepository.upsert(new ModelPricing(UUID.randomUUID(), modelName, prompt,
                completion, cached, thought, Instant.now()));
        }
    }

    @Transactional
    public void deleteModelPricing(String modelName) {
        pricingRepository.deleteByModelName(modelName);
    }

    /**
     * Prices the per-model token rows returned by the {@code calculate*} repository queries. Sums
     * per-model cost via {@link PricingCalculator} (scale-8 per bucket, scale-6 total) and the
     * token buckets across models. Cost stays in Java (not SQL) so the rounding matches the
     * persisted write path exactly (MNT-03).
     */
    /** Per-model accumulator, merging the (model, gateway_cache_status) groups back together. */
    private static final class ModelAcc {
        long pTokens;
        long cTokens;
        long caTokens;
        long tTokens;
        BigDecimal cost = BigDecimal.ZERO;
    }

    private CostReport aggregateModelRows(List<Map<String, Object>> rows) {
        // Empty result skips the pricing read (as before); otherwise price against a fresh
        // snapshot.
        return aggregateModelRows(rows, rows.isEmpty() ? null : loadPricingMap());
    }

    private CostReport aggregateModelRows(List<Map<String, Object>> rows,
        Map<String, ModelPricing> dbPrices) {
        if (rows.isEmpty()) {
            return new CostReport(Collections.emptyList(), 0, 0, 0, 0,
                BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP));
        }

        List<ModelCostBreakdown> breakdowns = new ArrayList<>();
        long totalPrompt = 0;
        long totalCompletion = 0;
        long totalCached = 0;
        long totalThought = 0;
        BigDecimal totalCost = BigDecimal.ZERO;

        // The queries group by (model, gateway_cache_status) so each row is uniformly replayed or
        // not — that is what lets a replay be priced at zero. The BREAKDOWN is per model though,
        // so the split is merged back here; leaving it through would list a model twice on the
        // dashboard, with nothing on screen explaining why.
        Map<String, ModelAcc> byModel = new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            String model = (String) row.get("model");
            long pTokens = getLong(row.get("p_tokens"));
            long cTokens = getLong(row.get("c_tokens"));
            long caTokens = getLong(row.get("ca_tokens"));
            long tTokens = getLong(row.get("t_tokens"));

            totalPrompt += pTokens;
            totalCompletion += cTokens;
            totalCached += caTokens;
            totalThought += tTokens;

            // A gateway-replayed call reported these tokens from a stored response body: the
            // provider was never reached and charged nothing, so it contributes no cost. Tokens
            // still count — they describe the exchange the model would have processed.
            BigDecimal modelCost = isReplayed(row) ? BigDecimal.ZERO
                : PricingCalculator.cost(model, pTokens, cTokens, caTokens, tTokens, dbPrices);
            totalCost = totalCost.add(modelCost);

            ModelAcc acc = byModel.computeIfAbsent(model, k -> new ModelAcc());
            acc.pTokens += pTokens;
            acc.cTokens += cTokens;
            acc.caTokens += caTokens;
            acc.tTokens += tTokens;
            acc.cost = acc.cost.add(modelCost);
        }

        byModel.forEach((model, acc) -> breakdowns.add(new ModelCostBreakdown(model, acc.pTokens,
            acc.cTokens, acc.caTokens, acc.tTokens, acc.cost.setScale(6, RoundingMode.HALF_UP))));

        return new CostReport(breakdowns, totalPrompt, totalCompletion, totalCached, totalThought,
            totalCost.setScale(6, RoundingMode.HALF_UP));
    }

    public FilteredCostExplorerReport getFilteredExplorerReport(String viewLevel,
        LocalDate startDate, LocalDate endDate, List<String> models, List<UUID> userIds,
        List<String> playbooks) {
        return getFilteredExplorerReport(viewLevel, startDate, endDate, models, userIds, null,
            playbooks);
    }

    public FilteredCostExplorerReport getFilteredExplorerReport(String viewLevel,
        LocalDate startDate, LocalDate endDate, List<String> models, List<UUID> userIds,
        List<String> sources, List<String> playbooks) {
        return getFilteredExplorerReport(viewLevel, startDate, endDate, models, userIds, sources,
            playbooks, null);
    }

    /**
     * Explorer report; the RAW view supports forward keyset pagination via {@code cursor} (an
     * opaque {@code "<instant>|<uuid>"} taken from a prior page's {@code nextCursor}; null/blank =
     * first page). MESSAGE and CONVERSATION are unpaged (their grouping would split across page
     * cuts).
     */
    public FilteredCostExplorerReport getFilteredExplorerReport(String viewLevel,
        LocalDate startDate, LocalDate endDate, List<String> models, List<UUID> userIds,
        List<String> sources, List<String> playbooks, String cursor) {

        Map<String, ModelPricing> dbPrices = loadPricingMap();

        boolean isRawLevel =
            "RAW".equalsIgnoreCase(viewLevel) || "CALL".equalsIgnoreCase(viewLevel);
        boolean isMessageLevel = "MESSAGE".equalsIgnoreCase(viewLevel);

        if (isRawLevel || isMessageLevel) {
            List<Map<String, Object>> rows;
            boolean hasMore = false;
            if (isRawLevel) {
                // Forward keyset page (Slice 3): fetch one extra row to detect whether more remain.
                RawCursor rc = decodeCursor(cursor);
                rows = costQueryRepository.explorerCallRowsPaged(startDate, endDate, models,
                    userIds, sources, playbooks, rc != null ? rc.createdAt() : null,
                    rc != null ? rc.id() : null, explorerRowCap + 1);
                hasMore = rows.size() > explorerRowCap;
                if (hasMore) {
                    rows = rows.subList(0, explorerRowCap);
                }
            } else {
                rows = costQueryRepository.explorerCallRows(startDate, endDate, models, userIds,
                    sources, playbooks, explorerRowCap);
                if (rows.size() >= explorerRowCap) {
                    // Not a silent cap: warn so the number isn't mistaken for the true total
                    // (PRF-01).
                    log.warn(
                        "Cost-explorer {} view truncated at the {}-row cap; narrow the filters "
                            + "or date range for complete figures.",
                        viewLevel, explorerRowCap);
                }
            }

            if (isRawLevel) {
                List<FilteredMessageRow> msgRows = new ArrayList<>();
                long totalP = 0, totalC = 0, totalCa = 0, totalT = 0;
                BigDecimal grandTotalCost = BigDecimal.ZERO;
                BigDecimal avoidedCost = BigDecimal.ZERO;
                long replayed = 0, forwarded = 0;

                for (Map<String, Object> row : rows) {
                    UUID id = (UUID) row.get("item_id");
                    UUID msgId = (UUID) row.get("message_id");
                    String itemType = (String) row.get("item_type");
                    String role = (String) row.get("role");
                    String playbook = (String) row.get("playbook");
                    String masProfile = (String) row.get("mas_profile");
                    String model = (String) row.get("model");
                    UUID convId = (UUID) row.get("conversation_id");
                    String convTitle = (String) row.get("conversation_title");
                    String userName = (String) row.get("user_name");
                    long p = getLong(row.get("prompt_tokens"));
                    long c = getLong(row.get("completion_tokens"));
                    long ca = getLong(row.get("cached_tokens"));
                    long t = getLong(row.get("thought_tokens"));
                    Timestamp ts = (Timestamp) row.get("created_at");
                    Instant createdAt = ts != null ? ts.toInstant() : null;

                    BigDecimal cost =
                        getModelCost(model != null ? model : "", p, c, ca, t, dbPrices);
                    // A replayed call reported these tokens from a stored body; nothing was
                    // charged for them, so the money moves from spend to savings.
                    if (isReplayed(row)) {
                        avoidedCost = avoidedCost.add(cost);
                        cost = BigDecimal.ZERO;
                        replayed++;
                    } else if (isGatewayRouted(row)) {
                        forwarded++;
                    }
                    long totalTokens = p + c + ca + t;

                    totalP += p;
                    totalC += c;
                    totalCa += ca;
                    totalT += t;
                    grandTotalCost = grandTotalCost.add(cost);

                    msgRows.add(new FilteredMessageRow(id, itemType, role, playbook, masProfile,
                        model, convId, convTitle, userName, p, c, ca, t, totalTokens,
                        cost.setScale(6, RoundingMode.HALF_UP), createdAt, 1L, msgId));
                }

                String nextCursor = (hasMore && !msgRows.isEmpty())
                    ? encodeCursor(msgRows.get(msgRows.size() - 1).createdAt(),
                        msgRows.get(msgRows.size() - 1).id())
                    : null;
                return new FilteredCostExplorerReport(Collections.emptyList(), msgRows, "RAW",
                    totalP, totalC, totalCa, totalT, (totalP + totalC + totalCa + totalT),
                    grandTotalCost.setScale(6, RoundingMode.HALF_UP), msgRows.size(), nextCursor,
                    replayed, forwarded, avoidedCost.setScale(6, RoundingMode.HALF_UP));
            } else {
                // isMessageLevel: group by message_id
                Map<UUID, MsgAcc> msgMap = new LinkedHashMap<>();
                long totalP = 0, totalC = 0, totalCa = 0, totalT = 0;
                BigDecimal grandTotalCost = BigDecimal.ZERO;
                BigDecimal avoidedCost = BigDecimal.ZERO;
                long replayed = 0, forwarded = 0;

                for (Map<String, Object> row : rows) {
                    UUID rawMsgId = (UUID) row.get("message_id");
                    UUID itemId = (UUID) row.get("item_id");
                    UUID keyId = rawMsgId != null ? rawMsgId : itemId;

                    String itemType = (String) row.get("item_type");
                    String role = (String) row.get("role");
                    String playbook = (String) row.get("playbook");
                    String masProfile = (String) row.get("mas_profile");
                    String model = (String) row.get("model");
                    UUID convId = (UUID) row.get("conversation_id");
                    String convTitle = (String) row.get("conversation_title");
                    String userName = (String) row.get("user_name");
                    long p = getLong(row.get("prompt_tokens"));
                    long c = getLong(row.get("completion_tokens"));
                    long ca = getLong(row.get("cached_tokens"));
                    long t = getLong(row.get("thought_tokens"));
                    Timestamp ts = (Timestamp) row.get("created_at");
                    Instant createdAt = ts != null ? ts.toInstant() : null;

                    BigDecimal cost =
                        getModelCost(model != null ? model : "", p, c, ca, t, dbPrices);
                    if (isReplayed(row)) {
                        avoidedCost = avoidedCost.add(cost);
                        cost = BigDecimal.ZERO;
                        replayed++;
                    } else if (isGatewayRouted(row)) {
                        forwarded++;
                    }

                    totalP += p;
                    totalC += c;
                    totalCa += ca;
                    totalT += t;
                    grandTotalCost = grandTotalCost.add(cost);

                    MsgAcc acc = msgMap.computeIfAbsent(keyId, k -> {
                        MsgAcc newAcc = new MsgAcc();
                        newAcc.messageId = keyId;
                        newAcc.itemType = itemType;
                        newAcc.role = role;
                        newAcc.playbook = playbook;
                        newAcc.masProfile = masProfile;
                        newAcc.conversationId = convId;
                        newAcc.conversationTitle = convTitle;
                        newAcc.userName = userName;
                        newAcc.createdAt = createdAt;
                        return newAcc;
                    });

                    if (model != null && !model.isBlank())
                        acc.models.add(model);
                    acc.callCount++;
                    acc.pTokens += p;
                    acc.cTokens += c;
                    acc.caTokens += ca;
                    acc.tTokens += t;
                    acc.cost = acc.cost.add(cost);
                }

                List<FilteredMessageRow> msgRows = new ArrayList<>();
                for (MsgAcc acc : msgMap.values()) {
                    long totalTokens = acc.pTokens + acc.cTokens + acc.caTokens + acc.tTokens;
                    String modelsStr = String.join(", ", acc.models);

                    msgRows.add(new FilteredMessageRow(acc.messageId, acc.itemType, acc.role,
                        acc.playbook, acc.masProfile, modelsStr, acc.conversationId,
                        acc.conversationTitle, acc.userName, acc.pTokens, acc.cTokens, acc.caTokens,
                        acc.tTokens, totalTokens, acc.cost.setScale(6, RoundingMode.HALF_UP),
                        acc.createdAt, acc.callCount, acc.messageId));
                }

                return new FilteredCostExplorerReport(Collections.emptyList(), msgRows, "MESSAGE",
                    totalP, totalC, totalCa, totalT, (totalP + totalC + totalCa + totalT),
                    grandTotalCost.setScale(6, RoundingMode.HALF_UP), msgRows.size(), null,
                    replayed, forwarded, avoidedCost.setScale(6, RoundingMode.HALF_UP));
            }
        } else {
            BigDecimal avoidedCost = BigDecimal.ZERO;
            long replayed = 0, forwarded = 0;
            List<Map<String, Object>> rows = costQueryRepository.explorerConversationRows(startDate,
                endDate, models, userIds, sources, playbooks, explorerRowCap);
            if (rows.size() >= explorerRowCap) {
                // PRF-01: the CONVERSATION group query is otherwise unbounded (esp.
                // timePreset='all',
                // which drops the date predicate); cap it and warn so the figure isn't mistaken for
                // the complete total.
                log.warn("Cost-explorer CONVERSATION view truncated at the {}-row cap; narrow the "
                    + "filters or date range for complete figures.", explorerRowCap);
            }

            class ConvAcc {
                UUID convId;
                String title;
                String userName;
                Instant lastActivity;
                Set<String> models = new LinkedHashSet<>();
                Set<String> masProfiles = new LinkedHashSet<>();
                Set<String> playbooks = new LinkedHashSet<>();
                long msgCount = 0;
                long stepCount = 0;
                long pTokens = 0, cTokens = 0, caTokens = 0, tTokens = 0;
                BigDecimal cost = BigDecimal.ZERO;
            }

            Map<UUID, ConvAcc> convMap = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                UUID convId = (UUID) row.get("conv_id");
                String title = (String) row.get("title");
                String userName = (String) row.get("user_name");
                Timestamp ts = (Timestamp) row.get("last_activity");
                Instant lastActivity = ts != null ? ts.toInstant() : null;
                String model = (String) row.get("model");
                String masProfile = (String) row.get("mas_profile");
                String playbook = (String) row.get("playbook");
                long p = getLong(row.get("p_tokens"));
                long c = getLong(row.get("c_tokens"));
                long ca = getLong(row.get("ca_tokens"));
                long t = getLong(row.get("t_tokens"));
                long msgC = getLong(row.get("msg_count"));
                long stepC = getLong(row.get("step_count"));

                BigDecimal cost = getModelCost(model != null ? model : "", p, c, ca, t, dbPrices);
                // Rows are grouped by gateway status, so each row is uniformly replayed or not.
                if (isReplayed(row)) {
                    avoidedCost = avoidedCost.add(cost);
                    cost = BigDecimal.ZERO;
                    replayed += msgC + stepC;
                } else if (isGatewayRouted(row)) {
                    forwarded += msgC + stepC;
                }

                ConvAcc acc = convMap.computeIfAbsent(convId, k -> {
                    ConvAcc newAcc = new ConvAcc();
                    newAcc.convId = convId;
                    newAcc.title = title;
                    newAcc.userName = userName;
                    newAcc.lastActivity = lastActivity;
                    return newAcc;
                });

                if (model != null && !model.isBlank())
                    acc.models.add(model);
                if (masProfile != null && !masProfile.isBlank())
                    acc.masProfiles.add(masProfile);
                if (playbook != null && !playbook.isBlank())
                    acc.playbooks.add(playbook);
                acc.msgCount += msgC;
                acc.stepCount += stepC;
                acc.pTokens += p;
                acc.cTokens += c;
                acc.caTokens += ca;
                acc.tTokens += t;
                acc.cost = acc.cost.add(cost);
            }

            List<FilteredConversationRow> convRows = new ArrayList<>();
            long totalP = 0, totalC = 0, totalCa = 0, totalT = 0;
            BigDecimal grandTotalCost = BigDecimal.ZERO;

            for (ConvAcc acc : convMap.values()) {
                totalP += acc.pTokens;
                totalC += acc.cTokens;
                totalCa += acc.caTokens;
                totalT += acc.tTokens;
                grandTotalCost = grandTotalCost.add(acc.cost);

                long totalTokens = acc.pTokens + acc.cTokens + acc.caTokens + acc.tTokens;
                String modelsStr = String.join(", ", acc.models);
                String masStr = String.join(", ", acc.masProfiles);
                String playbooksStr = String.join(", ", acc.playbooks);

                convRows.add(new FilteredConversationRow(acc.convId, acc.title, acc.userName,
                    modelsStr, masStr, playbooksStr, acc.msgCount, acc.stepCount, acc.pTokens,
                    acc.cTokens, acc.caTokens, acc.tTokens, totalTokens,
                    acc.cost.setScale(6, RoundingMode.HALF_UP), acc.lastActivity));
            }

            convRows.sort((r1, r2) -> r2.estimatedCostUsd().compareTo(r1.estimatedCostUsd()));

            return new FilteredCostExplorerReport(convRows, Collections.emptyList(), "CONVERSATION",
                totalP, totalC, totalCa, totalT, (totalP + totalC + totalCa + totalT),
                grandTotalCost.setScale(6, RoundingMode.HALF_UP), convRows.size(), null, replayed,
                forwarded, avoidedCost.setScale(6, RoundingMode.HALF_UP));
        }
    }

    /**
     * Snapshots the model-pricing table into a name-keyed map (one read; PRF-14 / MNT-03 dedup).
     */
    private Map<String, ModelPricing> loadPricingMap() {
        Map<String, ModelPricing> prices = new HashMap<>();
        for (ModelPricing mp : pricingRepository.findAll()) {
            prices.put(mp.modelName(), mp);
        }
        return prices;
    }

    private BigDecimal getModelCost(String model, long p, long c, long ca, long t,
        Map<String, ModelPricing> dbPrices) {
        return PricingCalculator.cost(model, p, c, ca, t, dbPrices);
    }

    /**
     * The overview fragment's four reports in one call, sharing a single pricing snapshot (PRF-14).
     * The four standalone methods each load {@code chat_model_pricing} independently — rendering
     * the overview through them costs four identical reads; this composite reads once and prices
     * global, top-users, top-conversations and MAS-profiles against the same map. Values are
     * identical to calling the four methods separately (a stable pricing table); the shared
     * snapshot is also more internally consistent.
     */
    public OverviewReport getOverviewReport(LocalDate startDate, LocalDate endDate, int topN) {
        Map<String, ModelPricing> dbPrices = loadPricingMap();
        CostReport report = aggregateModelRows(
            costQueryRepository.globalModelTokenRows(startDate, endDate), dbPrices);
        List<UserCostRow> topUsers = topUsersByCost(
            costQueryRepository.topUserTokenRows(startDate, endDate), dbPrices, topN);
        List<ConversationCostRow> topConversations = topConversationsByCost(
            costQueryRepository.topConversationTokenRows(startDate, endDate), dbPrices, topN);
        List<MasProfileCostRow> masProfiles = masProfilesByCost(
            costQueryRepository.masProfileTokenRows(startDate, endDate), dbPrices);
        return new OverviewReport(report, topUsers, topConversations, masProfiles);
    }

    public List<UserCostRow> getTopUsersByCost(LocalDate startDate, LocalDate endDate, int limit) {
        return topUsersByCost(costQueryRepository.topUserTokenRows(startDate, endDate),
            loadPricingMap(), limit);
    }

    private List<UserCostRow> topUsersByCost(List<Map<String, Object>> rows,
        Map<String, ModelPricing> dbPrices, int limit) {
        Map<UUID, UserCostRow> userMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            UUID userId = (UUID) row.get("user_id");
            String displayName = (String) row.get("display_name");
            String email = (String) row.get("email");
            String model = (String) row.get("model");
            long p = getLong(row.get("p_tokens"));
            long c = getLong(row.get("c_tokens"));
            long ca = getLong(row.get("ca_tokens"));
            long t = getLong(row.get("t_tokens"));

            // Replayed by the gateway: the provider was never reached and charged nothing.
            BigDecimal cost =
                isReplayed(row) ? BigDecimal.ZERO : getModelCost(model, p, c, ca, t, dbPrices);
            long tokens = p + c + ca + t;

            UserCostRow existing = userMap.get(userId);
            if (existing != null) {
                userMap.put(userId, new UserCostRow(userId, displayName, email,
                    existing.totalTokens() + tokens, existing.estimatedCostUsd().add(cost)));
            } else {
                userMap.put(userId, new UserCostRow(userId, displayName, email, tokens, cost));
            }
        }

        return userMap.values().stream()
            .sorted((u1, u2) -> u2.estimatedCostUsd().compareTo(u1.estimatedCostUsd())).limit(limit)
            .toList();
    }

    public List<ConversationCostRow> getTopConversationsByCost(LocalDate startDate,
        LocalDate endDate, int limit) {
        return topConversationsByCost(
            costQueryRepository.topConversationTokenRows(startDate, endDate), loadPricingMap(),
            limit);
    }

    private List<ConversationCostRow> topConversationsByCost(List<Map<String, Object>> rows,
        Map<String, ModelPricing> dbPrices, int limit) {
        Map<UUID, ConversationCostRow> convMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            UUID convId = (UUID) row.get("conv_id");
            String title = (String) row.get("title");
            String userName = (String) row.get("user_name");
            String model = (String) row.get("model");
            long p = getLong(row.get("p_tokens"));
            long c = getLong(row.get("c_tokens"));
            long ca = getLong(row.get("ca_tokens"));
            long t = getLong(row.get("t_tokens"));

            // Replayed by the gateway: the provider was never reached and charged nothing.
            BigDecimal cost =
                isReplayed(row) ? BigDecimal.ZERO : getModelCost(model, p, c, ca, t, dbPrices);
            long tokens = p + c + ca + t;

            ConversationCostRow existing = convMap.get(convId);
            if (existing != null) {
                convMap.put(convId, new ConversationCostRow(convId, title, userName,
                    existing.totalTokens() + tokens, existing.estimatedCostUsd().add(cost)));
            } else {
                convMap.put(convId, new ConversationCostRow(convId, title, userName, tokens, cost));
            }
        }

        return convMap.values().stream()
            .sorted((c1, c2) -> c2.estimatedCostUsd().compareTo(c1.estimatedCostUsd())).limit(limit)
            .toList();
    }

    public List<MasProfileCostRow> getMasProfilesByCost(LocalDate startDate, LocalDate endDate) {
        return masProfilesByCost(costQueryRepository.masProfileTokenRows(startDate, endDate),
            loadPricingMap());
    }

    private List<MasProfileCostRow> masProfilesByCost(List<Map<String, Object>> rows,
        Map<String, ModelPricing> dbPrices) {
        Map<String, MasProfileCostRow> profileMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String profileName = (String) row.get("profile_name");
            String model = (String) row.get("model");
            long p = getLong(row.get("p_tokens"));
            long c = getLong(row.get("c_tokens"));
            long ca = getLong(row.get("ca_tokens"));
            long t = getLong(row.get("t_tokens"));

            // Replayed by the gateway: the provider was never reached and charged nothing.
            BigDecimal cost =
                isReplayed(row) ? BigDecimal.ZERO : getModelCost(model, p, c, ca, t, dbPrices);
            long tokens = p + c + ca + t;

            MasProfileCostRow existing = profileMap.get(profileName);
            if (existing != null) {
                profileMap.put(profileName, new MasProfileCostRow(profileName,
                    existing.totalTokens() + tokens, existing.estimatedCostUsd().add(cost)));
            } else {
                profileMap.put(profileName, new MasProfileCostRow(profileName, tokens, cost));
            }
        }

        return profileMap.values().stream()
            .sorted((p1, p2) -> p2.estimatedCostUsd().compareTo(p1.estimatedCostUsd())).toList();
    }

    private static class MsgAcc {
        UUID messageId;
        String itemType;
        String role;
        String playbook;
        String masProfile;
        UUID conversationId;
        String conversationTitle;
        String userName;
        Set<String> models = new LinkedHashSet<>();
        long callCount = 0;
        long pTokens = 0, cTokens = 0, caTokens = 0, tTokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        Instant createdAt;
    }

    private long getLong(Object value) {
        if (value == null)
            return 0L;
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    /** Keyset cursor for the RAW explorer page: a row's ordering key {@code (created_at, id)}. */
    private record RawCursor(Instant createdAt, UUID id) {}

    /**
     * Encodes a keyset cursor as an opaque {@code "<instant>|<uuid>"} token for the next-page link.
     */
    private static String encodeCursor(Instant createdAt, UUID id) {
        if (createdAt == null || id == null) {
            return null;
        }
        return createdAt.toString() + "|" + id;
    }

    /**
     * Decodes a {@code "<instant>|<uuid>"} cursor; returns null for null/blank or malformed input,
     * so a bad cursor falls back to the first page rather than erroring (the value comes from the
     * URL).
     */
    private static RawCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int sep = cursor.lastIndexOf('|');
        if (sep <= 0 || sep == cursor.length() - 1) {
            return null;
        }
        try {
            return new RawCursor(Instant.parse(cursor.substring(0, sep)),
                UUID.fromString(cursor.substring(sep + 1)));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
