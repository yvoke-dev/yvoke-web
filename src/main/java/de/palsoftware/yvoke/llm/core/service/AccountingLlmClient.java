package de.palsoftware.yvoke.llm.core.service;

import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent;
import de.palsoftware.yvoke.llm.core.model.LlmCallFailedException;
import de.palsoftware.yvoke.llm.core.model.LlmGatewayInfo;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponse;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Decorator that turns "an LLM call happened" into an {@code llm_call_logs} row, for every caller,
 * without any caller taking part.
 *
 * <p>
 * Accounting used to be the caller's job: each of five call sites extracted usage and published
 * {@link LlmCallLoggedEvent} itself. {@code GeneralSummarizer} never did — its spend has never
 * appeared in any cost view — and nothing detected that, because there was no place where the
 * omission was visible. Attribution now comes from {@link LlmCallContextHolder}, which the entry
 * points already set, so a new caller gets accounting by construction and a caller that forgets the
 * context still produces a row, marked {@link LlmCallContextHolder#UNKNOWN_SOURCE}.
 *
 * <p>
 * One row per HTTP call, which is what the table's name always claimed. The chat path previously
 * wrote one row per <i>message</i>, summed over up to {@code app.ai.rag.max-iterations} calls;
 * per-message totals are now a {@code GROUP BY message_id}, and {@code messages} keeps its own
 * rollup columns either way.
 *
 * <p>
 * This is also the natural home for anything else that must see every call — per-user rate limiting
 * or budget enforcement — precisely because nothing can route around it.
 */
public class AccountingLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AccountingLlmClient.class);

    private final LlmClient delegate;
    private final ApplicationEventPublisher eventPublisher;

    public AccountingLlmClient(LlmClient delegate, ApplicationEventPublisher eventPublisher) {
        this.delegate = delegate;
        this.eventPublisher = eventPublisher;
    }

    /**
     * A call that reached the provider and then produced no usable answer — a safety or recitation
     * block — still consumed tokens the provider billed for. Those arrive on
     * {@link LlmCallFailedException}, so they are recorded before the failure propagates, matching
     * what {@link #generateStream} does by observing chunks.
     */
    @Override
    public LlmResponse generate(LlmRequest request) {
        long startNanos = System.nanoTime();
        LlmResponse response;
        try {
            response = delegate.generate(request);
        } catch (LlmCallFailedException e) {
            publish(request, e.usage(), null, startNanos);
            throw e;
        }
        publish(request, response == null ? null : response.usage(),
            response == null ? null : response.gateway(), startNanos);
        return response;
    }

    /**
     * Usage arrives inside the stream — in practice on the last chunk that carries it — so the
     * event can only be published once the stream ends. A stream that fails or is cancelled part
     * way still consumed tokens the provider billed for, so whatever usage was observed is recorded
     * before the failure propagates unchanged.
     */
    @Override
    public void generateStream(LlmRequest request, Consumer<LlmResponseChunk> onChunk) {
        long startNanos = System.nanoTime();
        AtomicReference<LlmUsage> observedUsage = new AtomicReference<>();
        AtomicReference<LlmGatewayInfo> observedGateway = new AtomicReference<>();
        try {
            delegate.generateStream(request, chunk -> {
                if (chunk != null && chunk.usage() != null) {
                    observedUsage.set(chunk.usage());
                }
                // Headers are stamped on every chunk, including ones carrying no usage, so this
                // is tracked separately rather than read off whichever chunk happened to have both.
                if (chunk != null && chunk.gateway() != null) {
                    observedGateway.set(chunk.gateway());
                }
                onChunk.accept(chunk);
            });
        } catch (RuntimeException e) {
            publish(request, observedUsage.get(), observedGateway.get(), startNanos);
            throw e;
        }
        publish(request, observedUsage.get(), observedGateway.get(), startNanos);
    }

    /**
     * A null usage means the provider reported nothing to bill, so no row is invented. Failures
     * here are swallowed: accounting must never break the call it is accounting for.
     *
     * <p>
     * The interrupt flag is cleared for the duration and restored afterwards. The listener behind
     * this publish is a synchronous {@code @EventListener} that runs JDBC — there is no
     * {@code @EnableAsync} in this application — and the streaming catch above reaches here with
     * the flag still set, because the provider clients detect cancellation with
     * {@code isInterrupted()}, a read that never clears. Hikari's connection acquisition parks,
     * sees the pre-set flag, and fails; the resulting {@code SQLException} is swallowed to a
     * {@code log.warn} both by the listener and by this method, so the in-flight — largest — call
     * of a cancelled turn silently leaves no {@code llm_call_logs} row. Sibling write sites
     * ({@code OrchestrationService}, {@code ChatMessageService}) already do this; doing it INSIDE
     * publish rather than at either call site is what makes forgetting it unrepresentable.
     *
     * <p>
     * Restoring is not optional: swallowing the interrupt would turn the user's Stop into an
     * apparently normal return, trading a lost ledger row for a lost cancellation.
     */
    private void publish(LlmRequest request, LlmUsage usage, LlmGatewayInfo gateway,
        long startNanos) {
        if (usage == null) {
            return;
        }
        boolean wasInterrupted = Thread.interrupted();
        try {
            LlmCallContextHolder.Context ctx = LlmCallContextHolder.get();
            eventPublisher
                .publishEvent(new LlmCallLoggedEvent(ctx == null ? null : ctx.conversationId(),
                    ctx == null ? null : ctx.messageId(), ctx == null ? null : ctx.agentRunId(),
                    ctx == null ? null : ctx.userId(), source(ctx), ctx == null ? null : ctx.role(),
                    request.model(), usage.promptTokens(), usage.completionTokens(),
                    usage.cachedTokens(), usage.thoughtTokens(), usage.totalTokens(),
                    elapsedMillis(startNanos), gateway == null ? null : gateway.cacheStatus(),
                    gateway == null ? null : gateway.logId()));
        } catch (Exception e) {
            log.warn("Failed to publish LLM accounting event for model {}: {}", request.model(),
                e.getMessage());
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String source(LlmCallContextHolder.Context ctx) {
        if (ctx == null || ctx.source() == null || ctx.source().isBlank()) {
            return LlmCallContextHolder.UNKNOWN_SOURCE;
        }
        return ctx.source();
    }

    private static int elapsedMillis(long startNanos) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
