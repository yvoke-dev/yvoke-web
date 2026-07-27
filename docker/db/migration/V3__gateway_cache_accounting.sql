-- Gateway cache accounting for llm_call_logs.
--
-- The app calls Gemini through the Cloudflare AI Gateway in native passthrough mode, and gateway
-- caching is enabled. On a cache HIT the gateway replays a stored response body verbatim —
-- including its original usageMetadata token counts — while the provider is never called and bills
-- nothing. Until now those replays were priced as if they had been purchased, so total_cost
-- over-reported spend by whatever the hit rate happened to be.
--
-- From here, total_cost is what was ACTUALLY BILLED (zero for a replayed call) and cost_avoided is
-- what the replayed calls would have cost. "Saved by caching" is therefore SUM(cost_avoided), and
-- list price is total_cost + cost_avoided.
--
-- Rows written before this migration pre-date the cache signal entirely: their gateway_cache_status
-- is NULL, which is indistinguishable from a call that never traversed a gateway (OpenRouter,
-- Voyage, plain Gemini). Any replays among them remain over-priced in total_cost and cannot be
-- backfilled — nothing recorded the status at the time.

ALTER TABLE llm_call_logs
    -- REPLAYED | FORWARDED | UNRECOGNIZED, or NULL when no AI gateway was in the path.
    -- Deliberately not HIT/MISS: those are Cloudflare's wire tokens, and this must never be read as
    -- a sibling of cached_tokens, which is Gemini's context caching — tokens that WERE billed, at a
    -- discounted rate.
    ADD COLUMN gateway_cache_status TEXT,

    -- cf-aig-log-id: joins this row to its entry in Cloudflare's own log, which is the only way to
    -- reconcile this ledger against their dashboard. Free-form upstream text, capped at 64 chars by
    -- LlmGatewayInfo before it ever reaches here.
    ADD COLUMN gateway_log_id TEXT,

    -- What this call would have cost had the gateway not replayed it. Zero for every call that
    -- actually reached the provider, so SUM over any slice is exactly the money the cache saved.
    ADD COLUMN cost_avoided NUMERIC(14, 6) NOT NULL DEFAULT 0;

-- Hit-rate and savings reporting filters on the status; the partial index keeps it off the
-- overwhelming majority of rows, which have no gateway status at all.
CREATE INDEX idx_llm_call_logs_gateway_cache_status ON llm_call_logs (gateway_cache_status)
    WHERE gateway_cache_status IS NOT NULL;
