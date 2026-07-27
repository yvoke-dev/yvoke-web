/*
 * Pure text/markup helpers for the chat thread — no DOM, no globals, no side effects.
 *
 * Companion to citation-render.js: same rule, same reason. Anything here can be called from a Node
 * test with no environment, so the logic that silently produces wrong-but-plausible output is under
 * test rather than only observable through a 20-second browser run. Collaborators that would
 * otherwise be module-level state in thread.js (the prompt list, the active playbook) are passed in
 * as parameters.
 */

/** HTML-escapes text for insertion into element content or a quoted attribute value. */
export function escapeHtml(text) {
    return String(text)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#039;");
}

/** Diagram types the UI hands to mermaid. Order is irrelevant; membership is not. */
const MERMAID_PREFIXES = [
    'graph ', 'graph\n', 'flowchart ', 'flowchart\n', 'sequenceDiagram', 'gantt', 'classDiagram',
    'stateDiagram', 'erDiagram', 'journey', 'pie', 'quadrantChart', 'xyChart', 'mindmap',
    'timeline', 'gitGraph', 'C4Context',
];

/** True when a code block's text looks like mermaid source rather than ordinary code. */
export function isMermaidText(text) {
    const trimmed = String(text).trim();
    return MERMAID_PREFIXES.some(function (prefix) {
        return trimmed.startsWith(prefix);
    });
}

/**
 * Normalizes and balances `<think>` tags so they never nest and are always closed.
 *
 * While streaming, a trailing unclosed `<think>` is left open on purpose — the block is still
 * arriving, and closing it early would make the renderer treat partial reasoning as finished.
 */
export function normalizeThinkTags(text, isStreaming) {
    if (!text) return '';

    let result = '';
    let pos = 0;
    let inside = false;

    while (pos < text.length) {
        const nextStart = text.indexOf('<think>', pos);
        const nextEnd = text.indexOf('</think>', pos);

        if (nextStart === -1 && nextEnd === -1) {
            result += text.substring(pos);
            break;
        }

        if (nextStart !== -1 && (nextEnd === -1 || nextStart < nextEnd)) {
            result += text.substring(pos, nextStart);
            if (inside) {
                result += '</think>\n';
            }
            result += '<think>';
            inside = true;
            pos = nextStart + 7;
        } else {
            result += text.substring(pos, nextEnd);
            if (inside) {
                result += '</think>';
                inside = false;
            }
            pos = nextEnd + 8;
        }
    }

    if (inside && !isStreaming) {
        result += '</think>';
    }

    return result;
}

/**
 * One wording for a failed send, shared by both transports. These branches had drifted once: the
 * streaming path mapped 403/404 to sentences while the async path — the default — showed a bare
 * "Request failed (403)."
 */
export function describeSendFailure(status, body) {
    if (status === 403) {
        return 'You do not have access to this conversation.';
    }
    if (status === 404) {
        return 'This conversation no longer exists.';
    }
    let message = 'Request failed (' + status + ').';
    try {
        const parsed = JSON.parse(body);
        if (parsed && parsed.error) {
            message = parsed.error;
        }
    } catch (e) {
        // Body was not JSON — keep the status-only wording.
    }
    return message;
}

/**
 * Decides what the async poll should do with a status the server reported.
 *
 * The client must never invent status text for a message the server has already authored. A
 * terminal message always arrives with server-written content — the generic system error for a
 * failure, the stop marker for a cancellation — so the cancellation notice below is only ever
 * needed for a *local* abort, where the fetch was torn down before any content came back.
 *
 * This existed inline as `status === 'done' || status === 'error'`, passing `status === 'error'`
 * as the abort flag: a run that failed was captioned "[Generation stopped by user]" underneath
 * the system error it had already been given. Anything that is not 'generating' is terminal, so
 * a status this function has never heard of ends the poll instead of recursing forever (the poll
 * re-arms via setTimeout and only the terminal branch clears the loader interval).
 */
export function pollTerminalDecision(status, abortedLocally) {
    if (abortedLocally === true) {
        return { terminal: true, showCancelNotice: true };
    }
    if (status === 'generating') {
        return { terminal: false, showCancelNotice: false };
    }
    return { terminal: true, showCancelNotice: false };
}

/**
 * The "[Generation stopped by user]" notice, for a genuine local abort only.
 *
 * `hasText` picks between appending under partial output and replacing an empty bubble. The
 * wording is asserted verbatim by the browser stop test (ChatControlsE2EIT).
 */
export function cancelledNoticeMarkup(hasText) {
    if (hasText) {
        return `<p class="aborted-text" style="color: var(--text-muted); font-style: italic; margin-top: 0.5rem; font-size: 0.85rem;">[Generation stopped by user]</p>`;
    }
    return `<span style="color: var(--text-muted); font-style: italic;">[Generation stopped by user]</span>`;
}

/** The spinner-dots + status-text block shown while the assistant is generating. */
export function syncLoaderMarkup(statusText) {
    return `
                <div class="sync-loader-container">
                    <div class="sync-loader-dots">
                        <span class="dot"></span>
                        <span class="dot"></span>
                        <span class="dot"></span>
                    </div>
                    <span class="sync-loader-text">${escapeHtml(statusText)}</span>
                </div>
            `;
}

/** The assistant message-bubble placeholder; sync vs streaming differ only by variantClass. */
export function assistantPlaceholderMarkup(assistantMsgId, variantClass) {
    return `
                <div class="message-row message-assistant ${variantClass}" id="${assistantMsgId}">
                    <div class="message-avatar"><img src="/images/logo-32.png" alt="Yvoke Logo" style="width: 22px; height: 22px;" /></div>
                    <div class="message-bubble">
                        <div class="message-content" data-raw-content="">${syncLoaderMarkup('Thinking')}</div>
                        <div class="message-meta" style="display: none;"></div>
                    </div>
                </div>
            `;
}

/**
 * Human label for a tool/prompt name: a curated label if one exists, else the prompt's own title,
 * else the name de-slugified. `labels` and `prompts` are injected rather than read from module
 * state so this is callable without the page.
 */
export function promptLabel(name, prompts, labels) {
    if (!name) return '';
    if (labels && labels[name]) {
        return labels[name];
    }
    if (Array.isArray(prompts)) {
        const found = prompts.find(p => p && p.name === name);
        if (found && found.title) {
            return found.title;
        }
    }
    return name
        .replace(/[-_]+/g, ' ')
        .replace(/\b[a-z]/g, ch => ch.toUpperCase());
}

/** Placeholder for the chat input: playbook-specific when one is active, else the generic hint. */
export function chatInputDefaultPlaceholder(activePlaybookName, prompts) {
    if (activePlaybookName) {
        const playbookObj = Array.isArray(prompts)
            ? prompts.find(p => p && p.name === activePlaybookName)
            : null;
        const title = playbookObj ? playbookObj.title : activePlaybookName;
        return `Add your question for "${title}"...`;
    }
    return 'Ask a question… (type "/" or click "+" to select a skill, Shift + Enter for new line, Enter to send)';
}
