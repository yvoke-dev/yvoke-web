-- Per-instance section-summary configuration for Confluence ingestion.
--
-- A Confluence crawl carries no operator form: it is started per instance, or by a "sync all"
-- loop that fans out over every enabled instance, so there is no request in which to configure
-- one. Every other per-crawl decision already lives on this row for that reason
-- (target_collection, target_tag, process_attachments) and is snapshotted into the job's settings
-- at enqueue; these two join them.
--
-- build_section_summaries defaults to FALSE, which is a deliberate behaviour change. Page imports
-- previously summarized unconditionally -- and, because no prompt was ever passed, did so with no
-- system prompt at all, which is why the OIM - Confluence collection's summaries read
-- "Here is a summary of the section:" instead of describing anything. Off-by-default means an
-- existing instance ingests without paying for an LLM call per section, and an operator turns it
-- on deliberately, together with the prompt it needs.
ALTER TABLE confluence_instances
    ADD COLUMN build_section_summaries BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE confluence_instances
    ADD COLUMN summarize_prompt TEXT;

-- Same shape as ck_confluence_instances_target_tag_not_blank: '' is not a prompt name, and
-- allowing it would let a blank round-trip as "configured" past the not-null check in
-- SummarizePromptEnqueueValidator.
ALTER TABLE confluence_instances
    ADD CONSTRAINT ck_confluence_instances_summarize_prompt_not_blank
        CHECK (summarize_prompt IS NULL OR summarize_prompt <> '');

-- The pair only makes sense together: summaries enabled with no prompt is precisely the state that
-- produced unusable summaries, so the database refuses it rather than leaving the enqueue validator
-- as the only thing standing between a misconfigured instance and a crawl.
ALTER TABLE confluence_instances
    ADD CONSTRAINT ck_confluence_instances_summaries_need_a_prompt
        CHECK (NOT build_section_summaries OR summarize_prompt IS NOT NULL);
