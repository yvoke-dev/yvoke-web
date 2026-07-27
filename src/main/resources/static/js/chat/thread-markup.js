/*
 * Pure markup builders for the chat thread.
 *
 * Every function here returns a string and touches no DOM. The DOM work that used to surround them
 * — creating the element, walking siblings, calling htmx.process — stays in thread.js.
 *
 * These matter more than their size suggests: each one interpolates untrusted text (LLM output,
 * admin-authored playbook fields, corpus filenames) into HTML that is then assigned via innerHTML,
 * outside DOMPurify. escapeHtml is imported rather than injected because it is itself pure and
 * tested; ThreadTemplateXssSafetyTest scans this file along with the rest of static/js/chat.
 */

import { escapeHtml } from './thread-text.js';

/** The "/" playbook autocomplete list. `highlightIndex` selects the keyboard-focused row. */
export function autocompleteOptionsMarkup(prompts, highlightIndex) {
    if (!Array.isArray(prompts)) return '';
    return prompts.map(function (p, idx) {
        const activeClass = idx === highlightIndex ? 'active' : '';
        return `
                    <div class="prompt-option ${activeClass}" data-action="select-playbook" data-index="${idx}" data-prompt-name="${escapeHtml(p.name)}" data-prompt-title="${escapeHtml(p.title)}">
                        <span class="prompt-option-title">${escapeHtml(p.title)}</span>
                        <span class="prompt-option-desc">${escapeHtml(p.description)}</span>
                    </div>
                `;
    }).join('');
}

/**
 * Query string for GET /document/citation.
 *
 * Values are percent-encoded: an id containing `&`, `#` or a space would otherwise truncate or
 * corrupt the request.
 */
export function citationQueryParam(chunkId, documentId) {
    if (chunkId) return `chunkId=${encodeURIComponent(chunkId)}`;
    if (documentId) return `documentId=${encodeURIComponent(documentId)}`;
    return '';
}

/** A clarifying-question card the user has already answered. */
export function clarifyCardAnsweredMarkup(questionText, answerText) {
    return `
                        <div class="question-title">❓ Clarification Provided</div>
                        <div class="question-text">${escapeHtml(questionText)}</div>
                        <div class="clarified-badge">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="margin-right: 2px;">
                                <polyline points="20 6 9 17 4 12"></polyline>
                            </svg>
                            Clarified: "${escapeHtml(answerText)}"
                        </div>
                    `;
}

/** A clarifying-question card awaiting an answer, with optional preset option chips. */
export function clarifyCardActiveMarkup(questionText, options) {
    let optionsHtml = '';
    const opts = Array.isArray(options) ? options : [];
    if (opts.length > 0) {
        optionsHtml += '<div class="option-chips">';
        opts.forEach(function (opt) {
            optionsHtml += `<button type="button" class="option-chip" data-answer="${escapeHtml(opt)}" data-action="submit-clarification">${escapeHtml(opt)}</button>`;
        });
        optionsHtml += '</div>';
        optionsHtml += '<div class="custom-input-label">or provide custom response:</div>';
    }

    return `
                        <div class="question-title">❓ Clarification Required</div>
                        <div class="question-text">${escapeHtml(questionText)}</div>
                        <div class="question-input-wrapper">
                            ${optionsHtml}
                            <textarea class="question-answer-input" placeholder="Type your response here... (Press Enter to submit, Shift + Enter for new line)" rows="2" data-action="clarify-input"></textarea>
                            <button type="button" class="btn-submit-answer" data-action="submit-clarification">Submit Response</button>
                        </div>
                    `;
}

/**
 * The "this playbook may suit your question better" card. `switchTitle` is null when no alternative
 * playbook was suggested, in which case only the "Send Anyway" action is offered.
 */
export function preflightCardMarkup(reason, switchName, switchTitle) {
    const switchButtonHtml = switchName
        ? `<button type="button" class="btn-preflight-switch" data-action="switch-and-send" data-switch-name="${escapeHtml(switchName)}" data-switch-title="${escapeHtml(switchTitle)}">Switch to ${escapeHtml(switchTitle)}</button>`
        : '';

    return `
                <div class="preflight-warning-card" id="preflight-warning-card-el">
                    <div class="preflight-warning-header">
                        <span class="warning-icon">⚠️</span> Playbook Recommendation
                    </div>
                    <div class="preflight-warning-reason">
                        ${escapeHtml(reason)}
                    </div>
                    <div class="preflight-warning-actions">
                        ${switchButtonHtml}
                        <button type="button" class="btn-preflight-anyway" data-action="send-anyway">Send Anyway</button>
                    </div>
                </div>
            `;
}

/** The optimistic user-message bubble appended on submit. */
export function userMessageMarkup(userMsgId, content, playbookTagHtml) {
    return `
                <div class="message-row message-user" id="${userMsgId}">
                    <div class="message-avatar">U</div>
                    <div class="message-bubble">
                        ${playbookTagHtml || ''}
                        <div class="message-content">${escapeHtml(content)}</div>
                    </div>
                </div>
            `;
}

/** The 👍/👎 row. htmx attributes are built here; htmx.process() is the caller's job. */
export function feedbackButtonsMarkup(messageId) {
    return `
                    <div class="feedback-buttons-container" id="feedback-container-${messageId}">
                        <div class="feedback-rating-row">
                            <button class="feedback-btn feedback-thumbs-up"
                                    hx-post="/chat/message/${messageId}/feedback?rating=1"
                                    hx-target="#feedback-container-${messageId}"
                                    hx-swap="outerHTML"
                                    title="Thumbs up">
                                👍
                            </button>
                            <button class="feedback-btn feedback-thumbs-down"
                                    hx-post="/chat/message/${messageId}/feedback?rating=-1"
                                    hx-target="#feedback-container-${messageId}"
                                    hx-swap="outerHTML"
                                    title="Thumbs down">
                                👎
                            </button>
                        </div>
                    </div>
                `;
}

/** The token-usage line. Renders nothing unless there is a positive prompt-token count. */
export function tokenUsageMarkup(promptTokens, completionTokens, totalTokens, cachedTokens,
    thoughtTokens) {
    if (!promptTokens || !(parseInt(promptTokens) > 0)) {
        return '';
    }
    const cachedHtml = (cachedTokens && parseInt(cachedTokens) > 0)
        ? ` | Cached: <span>${cachedTokens}</span>` : '';
    const thoughtsHtml = (thoughtTokens && parseInt(thoughtTokens) > 0)
        ? ` | Thoughts: <span>${thoughtTokens}</span>` : '';
    return `
                        <div class="token-usage">
                            Prompt: <span>${promptTokens}</span>${cachedHtml} | Completion: <span>${completionTokens}</span>${thoughtsHtml} | Total: <span>${totalTokens}</span>
                        </div>
                    `;
}

/**
 * Reads token counts off a message payload that may use either camelCase or snake_case.
 *
 * The async status endpoint and the SSE path disagree on casing, so a reader that knows only one
 * shows a blank usage line rather than failing — which is why this is worth pinning.
 */
export function pickTokenCounts(msg) {
    const pick = (camel, snake) => (msg[camel] !== undefined ? msg[camel] : msg[snake]);
    return {
        promptTokens: pick('promptTokens', 'prompt_tokens'),
        completionTokens: pick('completionTokens', 'completion_tokens'),
        totalTokens: pick('totalTokens', 'total_tokens'),
        cachedTokens: pick('cachedTokens', 'cached_tokens'),
        thoughtTokens: pick('thoughtTokens', 'thought_tokens'),
    };
}

/**
 * The loader caption while an answer is still streaming and has produced no visible text yet:
 * the most recent tool being called, or "Thinking".
 */
export function streamingStatusText(rawText) {
    const toolMatches = [...String(rawText || '')
        .matchAll(/🔧\s*\*?Calling\s+tool:\s*\*?\s*(\w+)/gi)];
    if (toolMatches.length === 0) {
        return 'Thinking';
    }
    return 'Running ' + toolMatches[toolMatches.length - 1][1].replace(/_/g, ' ');
}
