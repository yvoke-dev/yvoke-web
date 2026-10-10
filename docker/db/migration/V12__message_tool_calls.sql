-- Persists single-agent tool call traces for evaluation and debugging.
-- Stores the verbatim tool result; a periodic retention job can purge older rows.
CREATE TABLE message_tool_calls (
    id            UUID PRIMARY KEY,
    message_id    UUID NOT NULL CONSTRAINT fk_message_tool_calls_message REFERENCES messages(id) ON DELETE CASCADE,
    seq           INT  NOT NULL,           -- 0-based sequence within the message
    tool_call_id  TEXT NOT NULL,           -- LlmToolCall.id, matches the banner #id
    tool_name     TEXT NOT NULL,
    arguments     TEXT,                    -- Raw JSON string produced by the model
    result        TEXT,                    -- Verbatim tool output or error string
    is_error      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_message_tool_calls_message_seq UNIQUE (message_id, seq)
);
