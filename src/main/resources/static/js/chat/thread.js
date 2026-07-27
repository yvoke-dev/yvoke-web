/*
 * Chat thread UI — extracted from chat/thread.html (MNT-02/15).
 * Loaded as an ES module (MNT-19): all behaviour is wired via `data-action` + the delegated
 * listeners below, so these functions no longer need to be globals for inline handlers; module
 * scope keeps them out of the global namespace. Server data arrives via `window.CHAT_CONFIG`
 * (set by the inline bootstrap in the template) and is captured once, below.
 *
 * The pure string→string half of message rendering lives in the sibling modules below, so it can be
 * tested without a browser (src/test/js/*.test.js); everything that touches the DOM stays here.
 *   citation-render.js — citation tokens: protect, restore, linkify, badge
 *   thread-text.js     — escaping, think-tag balancing, small markup builders
 *   markdown-render.js — the markdown pipeline and the code-block/mermaid renderer
 */

import { formatCitations } from './citation-render.js';

import {
    assistantPlaceholderMarkup,
    cancelledNoticeMarkup,
    chatInputDefaultPlaceholder,
    describeSendFailure,
    escapeHtml,
    pollTerminalDecision,
    promptLabel as promptLabelOf,
    syncLoaderMarkup,
} from './thread-text.js';

import {
    installCodeRenderer,
    renderMarkdown as renderMarkdownPipeline,
    wrapToolCalls,
} from './markdown-render.js';

import {
    autocompleteOptionsMarkup,
    citationQueryParam,
    clarifyCardActiveMarkup,
    clarifyCardAnsweredMarkup,
    feedbackButtonsMarkup,
    pickTokenCounts,
    preflightCardMarkup,
    streamingStatusText,
    tokenUsageMarkup,
    userMessageMarkup,
} from './thread-markup.js';

import { createSseAccumulator } from './sse-accumulator.js';

        // Server-provided config, read once from the inline bootstrap (see chat/thread.html).
        const CHAT_CONFIG = window.CHAT_CONFIG || {};

        // --- Assistant "thinking" loader (MNT-16: one source for the placeholder markup + rotation,
        //     previously copy-pasted across the sync / streaming / resume paths) ---

        // Rotating status lines cycled in the loader while a response generates.
        const LOADER_TEXTS = [
            "Thinking",
            "Searching the knowledge base",
            "Analyzing retrieved resources",
            "Formulating the response",
            "Polishing the answer"
        ];

        // The spinner-dots + status-text block shown while the assistant is generating.

        // The assistant message-bubble placeholder; sync vs streaming differ only by variantClass.

        // Cycles LOADER_TEXTS through the given .sync-loader-text element (fade every 3.5s).
        // Returns the interval id so the caller can clearInterval() when generation ends.
        function startLoaderTextRotation(loaderTextEl) {
            let textIndex = 0;
            return setInterval(() => {
                if (loaderTextEl) {
                    textIndex = (textIndex + 1) % LOADER_TEXTS.length;
                    loaderTextEl.style.opacity = 0;
                    setTimeout(() => {
                        loaderTextEl.textContent = LOADER_TEXTS[textIndex];
                        loaderTextEl.style.opacity = 1;
                    }, 300);
                }
            }, 3500);
        }

        // --- Event delegation (MNT-19): the chat UI previously wired behaviour through inline
        //     on* attributes (onclick/onkeydown/onmouseenter) in both server-rendered HTML and
        //     JS-generated strings, which forced every handler to be a global and mixed an inline
        //     paradigm into the app. Those attributes are replaced with `data-action` (+ typed
        //     `data-*` params) routed through these three document-level listeners. Handlers remain
        //     classic top-level functions (a full ES-module split is a separate follow-on). Attached
        //     at document level so dynamically-inserted content (citations, clarify cards, preflight
        //     cards, autocomplete options) needs no per-element wiring. ---
        document.addEventListener('click', function (event) {
            const el = event.target.closest('[data-action]');
            if (!el) return;
            switch (el.dataset.action) {
                case 'select-playbook':
                    selectPlaybook(el.dataset.promptName, el.dataset.promptTitle);
                    break;
                case 'clear-playbook':
                    clearPlaybook();
                    break;
                case 'toggle-citation':
                    toggleCitation(event, el, el.dataset.chunkId || null,
                        el.dataset.documentId || null);
                    break;
                case 'submit-clarification':
                    submitClarificationAnswer(el);
                    break;
                case 'switch-and-send':
                    switchAndSend(el.dataset.switchName, el.dataset.switchTitle);
                    break;
                case 'send-anyway':
                    sendAnyway();
                    break;
                case 'close-citation-dialog': {
                    const dlg = document.getElementById('citation-dialog');
                    if (dlg) dlg.close();
                    break;
                }
            }
        });

        // Clarify-answer textarea submits on Enter (data-action="clarify-input").
        document.addEventListener('keydown', function (event) {
            const el = event.target.closest('[data-action="clarify-input"]');
            if (el) handleClarifyKeydown(event, el);
        });

        // Autocomplete option hover-highlight. mouseenter doesn't bubble, so delegate via mouseover
        // (which does) on the popup; each option carries its list index in data-index.
        (function () {
            const popup = document.getElementById('autocomplete-popup');
            if (!popup) return;
            popup.addEventListener('mouseover', function (event) {
                const opt = event.target.closest('.prompt-option[data-index]');
                if (opt) setHighlight(parseInt(opt.dataset.index, 10));
            });
        })();

        // Initialize Mermaid
        if (typeof mermaid !== 'undefined') {
            mermaid.initialize({
                startOnLoad: false,
                theme: 'dark',
                suppressErrorAlerts: true
            });
        }

        // Helper to check if a block of code looks like a Mermaid diagram syntax

        // Code-block/mermaid rendering lives in markdown-render.js (it shares the streaming
        // flag with renderMarkdown, so the two cannot be separated).
        if (typeof marked !== 'undefined') {
            installCodeRenderer(marked);
        }

        const messagesContainer = document.getElementById('chat-messages');
        const chatForm = document.getElementById('chat-form');
        const chatInput = document.getElementById('chat-input');

        let currentAbortController = null;

        // Messages already finalized by the polling path. Guards against a stale poll timer
        // re-finalizing a finished message and wiping the feedback widget out from under the user
        // (see pollMessageStatus).
        const finalizedMessageIds = new Set();

        const sendStopButton = document.getElementById('send-stop-button');
        if (sendStopButton) {
            sendStopButton.addEventListener('click', function() {
                if (currentAbortController) {
                    currentAbortController.abort();
                    // Signal the server to interrupt the agentic loop
                    const conversationId = CHAT_CONFIG.conversationId;
                    fetch(`/chat/${conversationId}/stop`, { method: 'POST' }).catch(() => {});
                } else {
                    if (chatInput.value.trim() !== '') {
                        chatForm.requestSubmit();
                    }
                }
            });
        }

        const actionAddBtn = document.getElementById('action-add-btn');
        if (actionAddBtn) {
            actionAddBtn.addEventListener('click', function(e) {
                e.stopPropagation();
                const popup = document.getElementById('autocomplete-popup');
                const isPopupOpen = popup && popup.style.display === 'flex';
                if (isPopupOpen) {
                    hideAutocomplete();
                } else if (mcpPrompts.length > 0) {
                    showAutocomplete(mcpPrompts);
                    chatInput.focus();
                }
            });
        }

        document.addEventListener('click', function(e) {
            const popup = document.getElementById('autocomplete-popup');
            if (popup && popup.style.display === 'flex') {
                const isClickInside = popup.contains(e.target) || chatInput.contains(e.target) || (actionAddBtn && actionAddBtn.contains(e.target));
                if (!isClickInside) {
                    hideAutocomplete();
                }
            }
        });

        function updateSendStopButtonState() {
            const sendStopBtn = document.getElementById('send-stop-button');
            if (!sendStopBtn) return;
            
            const iconSend = sendStopBtn.querySelector('.icon-send');
            const iconStop = sendStopBtn.querySelector('.icon-stop');
            
            if (currentAbortController) {
                sendStopBtn.classList.add('state-stop');
                sendStopBtn.classList.remove('state-send-active', 'state-send-disabled');
                sendStopBtn.disabled = false;
                const tooltipEl = sendStopBtn.querySelector('.custom-tooltip');
                if (tooltipEl) tooltipEl.textContent = "Stop generating";
                if (iconSend) iconSend.style.display = 'none';
                if (iconStop) iconStop.style.display = 'block';
            } else {
                sendStopBtn.classList.remove('state-stop');
                if (chatInput.value.trim() === '') {
                    sendStopBtn.classList.add('state-send-disabled');
                    sendStopBtn.classList.remove('state-send-active');
                    sendStopBtn.disabled = true;
                    const tooltipEl = sendStopBtn.querySelector('.custom-tooltip');
                    if (tooltipEl) tooltipEl.textContent = "Send message (disabled)";
                } else {
                    sendStopBtn.classList.add('state-send-active');
                    sendStopBtn.classList.remove('state-send-disabled');
                    sendStopBtn.disabled = false;
                    const tooltipEl = sendStopBtn.querySelector('.custom-tooltip');
                    if (tooltipEl) tooltipEl.textContent = "Send message";
                }
                if (iconSend) iconSend.style.display = 'block';
                if (iconStop) iconStop.style.display = 'none';
            }
        }

        const isReadOnly = CHAT_CONFIG.isReadOnly;

        // Streaming Toggle Handling
        let streamingEnabled = false;
        if (!isReadOnly) {
            const dbStreaming = CHAT_CONFIG.streaming;
            if (dbStreaming !== null && dbStreaming !== undefined) {
                streamingEnabled = (dbStreaming === true || dbStreaming === 'true');
            } else {
                const localStreaming = localStorage.getItem('userDefaultStreaming');
                if (localStreaming !== null) {
                    streamingEnabled = (localStreaming === 'true');
                }
            }
        }

        const modeStandardBtn = document.getElementById('mode-standard-btn');
        const modeStreamingBtn = document.getElementById('mode-streaming-btn');

        function updateStreamingUI() {
            if (!modeStandardBtn || !modeStreamingBtn) return;
            if (streamingEnabled) {
                modeStreamingBtn.classList.add('active');
                modeStandardBtn.classList.remove('active');
            } else {
                modeStandardBtn.classList.add('active');
                modeStreamingBtn.classList.remove('active');
            }
        }
        updateStreamingUI();

        function setStreamingMode(enabled) {
            if (streamingEnabled === enabled) return;
            streamingEnabled = enabled;
            updateStreamingUI();
            localStorage.setItem('userDefaultStreaming', streamingEnabled);

            updateShowThinkingUI();
            document.body.classList.toggle('hide-thinking-process', !showThinkingEnabled && !streamingEnabled);

            const conversationId = CHAT_CONFIG.conversationId;
            fetch(`/chat/${conversationId}/streaming?enabled=${streamingEnabled}`, {
                method: 'POST'
            })
            .then(response => {
                if (!response.ok) {
                    console.error('Failed to update streaming settings in DB');
                }
            })
            .catch(error => {
                console.error('Error updating streaming settings in DB:', error);
            });
        }

        if (modeStandardBtn) {
            modeStandardBtn.addEventListener('click', () => setStreamingMode(false));
        }
        if (modeStreamingBtn) {
            modeStreamingBtn.addEventListener('click', () => setStreamingMode(true));
        }

        // MCP Prompts script state and functions
        const mcpPrompts = CHAT_CONFIG.prompts || [];
        const PROMPT_LABELS = {
            // MCP Tools (src/main/java/de/palsoftware/yvoke/mcp/tools)
            'search_corpus': 'Search Corpus',
            'get_graph_neighbors': 'Get Graph Neighbors',
            'get_json_schema': 'Get JSON Schema',
            'get_section': 'Get Section',
            'get_toc': 'Get Table of Contents',
            'list_documents': 'List Documents',
            'query_json_objects': 'Query JSON Objects',
            'search_graph_entities': 'Search Graph Entities',
            'verify_citations': 'Verify Citations',
            'ask_clarifying_question': 'Ask Clarifying Question'
        };


        let autocompleteHighlightIndex = 0;
        let filteredAutocompletePrompts = [];
        const autocompletePopup = document.getElementById('autocomplete-popup');

        function selectPlaybook(name, title) {
            if (typeof orchestratorMode !== 'undefined' && orchestratorMode) return;
            activePlaybookName = name;
            document.getElementById('chat-prompt-name').value = name;
            
            const warningEl = document.getElementById('playbook-validation-warning');
            if (warningEl) {
                warningEl.style.display = 'none';
            }
            
            const tagEl = document.getElementById('active-playbook-tag');
            if (tagEl) {
                tagEl.textContent = '📋 ' + title;
            }
            const activePlaybookEl = document.getElementById('active-playbook');
            if (activePlaybookEl) {
                activePlaybookEl.style.display = 'flex';
            }
            
            chatInput.placeholder = `Add your question for "${title}"...`;
            chatInput.focus();
            
            hideAutocomplete();
            
            const chipsEl = document.getElementById('prompt-chips');
            if (chipsEl) {
                chipsEl.style.display = 'none';
            }
        }

        function clearPlaybook() {
            activePlaybookName = null;
            const promptInput = document.getElementById('chat-prompt-name');
            if (promptInput) promptInput.value = '';
            const activePlaybookEl = document.getElementById('active-playbook');
            if (activePlaybookEl) {
                activePlaybookEl.style.display = 'none';
            }
            if (typeof chatInput !== 'undefined' && chatInput) {
                chatInput.placeholder = chatInputDefaultPlaceholder(activePlaybookName, mcpPrompts);
            }
            
            const chipsEl = document.getElementById('prompt-chips');
            const messagesContainer = document.getElementById('chat-messages');
            const messages = messagesContainer ? messagesContainer.querySelectorAll('.message-row:not(#prompt-chips)') : [];
            if (chipsEl && messages.length === 0 && (typeof orchestratorMode === 'undefined' || !orchestratorMode)) {
                chipsEl.style.display = 'flex';
            }
            if (typeof chatInput !== 'undefined' && chatInput && document.activeElement === chatInput) {
                chatInput.focus();
            }
        }

        function showAutocomplete(promptsList) {
            if (typeof orchestratorMode !== 'undefined' && orchestratorMode) return;
            filteredAutocompletePrompts = promptsList;
            if (autocompleteHighlightIndex >= filteredAutocompletePrompts.length) {
                autocompleteHighlightIndex = 0;
            }
            
            const html = autocompleteOptionsMarkup(filteredAutocompletePrompts, autocompleteHighlightIndex);
            if (autocompletePopup) {
                autocompletePopup.innerHTML = html;
                autocompletePopup.style.display = 'flex';
            }
        }

        function hideAutocomplete() {
            if (autocompletePopup) {
                autocompletePopup.style.display = 'none';
            }
            filteredAutocompletePrompts = [];
        }

        function setHighlight(index) {
            autocompleteHighlightIndex = index;
            if (!autocompletePopup) return;
            const options = autocompletePopup.querySelectorAll('.prompt-option');
            options.forEach((opt, idx) => {
                if (idx === index) {
                    opt.classList.add('active');
                } else {
                    opt.classList.remove('active');
                }
            });
        }

        // Orchestrator (multi-agent) profile handling. When a profile is selected the run is a
        // multi-agent orchestration (async only): the playbook picker + model/thinking/mode
        // controls are hidden and the mandatory-playbook preflight is skipped.
        let activePlaybookName = null;
        const orchestratorProfileSelector = document.getElementById('orchestrator-profile-selector');
        let orchestratorMode = !!(orchestratorProfileSelector && orchestratorProfileSelector.value);

        function applyOrchestratorModeUI() {
            ['model-selector', 'thinking-level-selector', 'action-add-btn', 'mode-standard-btn',
             'mode-streaming-btn', 'show-thinking-btn'].forEach(id => {
                const el = document.getElementById(id);
                if (el) el.style.display = orchestratorMode ? 'none' : '';
            });
            const promptChips = document.getElementById('prompt-chips');
            if (orchestratorMode) {
                const warn = document.getElementById('playbook-validation-warning');
                if (warn) warn.style.display = 'none';
                if (promptChips) promptChips.style.display = 'none';
                hideAutocomplete();
                clearPlaybook();
            } else {
                const messagesContainer = document.getElementById('chat-messages');
                const messages = messagesContainer ? messagesContainer.querySelectorAll('.message-row:not(#prompt-chips)') : [];
                if (promptChips && messages.length === 0 && !activePlaybookName) {
                    promptChips.style.display = 'flex';
                }
            }
        }
        applyOrchestratorModeUI();

        if (orchestratorProfileSelector && !isReadOnly) {
            orchestratorProfileSelector.addEventListener('change', function() {
                const name = orchestratorProfileSelector.value || '';
                orchestratorMode = !!name;
                applyOrchestratorModeUI();
                const conversationId = CHAT_CONFIG.conversationId;
                const params = new URLSearchParams();
                if (name) params.append('name', name);
                fetch(`/chat/${conversationId}/orchestrator-profile?${params.toString()}`, { method: 'POST' })
                    .then(r => { if (!r.ok) console.error('Failed to update orchestrator profile'); })
                    .catch(e => console.error('Error updating orchestrator profile', e));
            });
        }

        // Show Thinking Toggle Handling
        let showThinkingEnabled = false;
        const dbShowThinking = CHAT_CONFIG.showThinking;
        if (dbShowThinking !== null && dbShowThinking !== undefined) {
            showThinkingEnabled = (dbShowThinking === true || dbShowThinking === 'true');
        } else {
            const localShowThinking = localStorage.getItem('userDefaultShowThinking');
            if (localShowThinking !== null) {
                showThinkingEnabled = (localShowThinking === 'true');
            }
        }

        const showThinkingBtn = document.getElementById('show-thinking-btn');

        function updateShowThinkingUI() {
            if (!showThinkingBtn) return;
            const tooltipEl = showThinkingBtn.querySelector('.custom-tooltip');
            if (showThinkingEnabled || streamingEnabled) {
                showThinkingBtn.classList.add('active');
                if (tooltipEl) {
                    tooltipEl.textContent = streamingEnabled 
                        ? "Show thinking & tools (always enabled when streaming)" 
                        : "Show thinking & tools (enabled)";
                }
                if (streamingEnabled) {
                    showThinkingBtn.style.opacity = '0.6';
                    showThinkingBtn.style.cursor = 'not-allowed';
                } else {
                    showThinkingBtn.style.opacity = '';
                    showThinkingBtn.style.cursor = '';
                }
            } else {
                showThinkingBtn.classList.remove('active');
                if (tooltipEl) tooltipEl.textContent = "Show thinking & tools (disabled)";
                showThinkingBtn.style.opacity = '';
                showThinkingBtn.style.cursor = '';
            }
        }
        updateShowThinkingUI();

        function toggleShowThinking() {
            if (streamingEnabled) return;
            showThinkingEnabled = !showThinkingEnabled;
            updateShowThinkingUI();
            localStorage.setItem('userDefaultShowThinking', showThinkingEnabled);

            // Dynamically update visibility of current messages
            document.body.classList.toggle('hide-thinking-process', !showThinkingEnabled);

            if (isReadOnly) return;

            const conversationId = CHAT_CONFIG.conversationId;
            fetch(`/chat/${conversationId}/show-thinking?enabled=${showThinkingEnabled}`, {
                method: 'POST'
            })
            .then(response => {
                if (!response.ok) {
                    console.error('Failed to update show-thinking settings in DB');
                }
            })
            .catch(error => {
                console.error('Error updating show-thinking settings in DB:', error);
            });
        }

        if (showThinkingBtn) {
            showThinkingBtn.addEventListener('click', toggleShowThinking);
        }

        const modelSelector = document.getElementById('model-selector');
        if (modelSelector) {
            modelSelector.addEventListener('change', function() {
                const model = this.value;
                const conversationId = CHAT_CONFIG.conversationId;
                const formData = new FormData();
                formData.append('model', model);
                
                fetch(`/chat/${conversationId}/model`, {
                    method: 'POST',
                    body: formData
                })
                .then(response => {
                    if (!response.ok) {
                        console.error('Failed to update model settings');
                    }
                })
                .catch(error => {
                    console.error('Error updating model settings:', error);
                });
            });
        }

        const thinkingLevelSelector = document.getElementById('thinking-level-selector');
        if (thinkingLevelSelector) {
            thinkingLevelSelector.addEventListener('change', function() {
                const level = this.value;
                const conversationId = CHAT_CONFIG.conversationId;
                const formData = new FormData();
                formData.append('level', level);
                
                fetch(`/chat/${conversationId}/thinking-level`, {
                    method: 'POST',
                    body: formData
                })
                .then(response => {
                    if (!response.ok) {
                        console.error('Failed to update thinking level settings');
                    }
                })
                .catch(error => {
                    console.error('Error updating thinking level settings:', error);
                });
            });
        }

        function adjustChatInputHeight() {
            if (!chatInput) return;
            chatInput.style.height = 'auto';
            const computed = window.getComputedStyle(chatInput);
            const lineHeight = parseFloat(computed.lineHeight) || 22;
            const paddingTop = parseFloat(computed.paddingTop) || 12;
            const paddingBottom = parseFloat(computed.paddingBottom) || 12;
            const borderTop = parseFloat(computed.borderTopWidth) || 1;
            const borderBottom = parseFloat(computed.borderBottomWidth) || 1;
            const padding = paddingTop + paddingBottom + borderTop + borderBottom;
            
            const minHeight = lineHeight * 3 + padding;
            const maxHeight = lineHeight * 8 + padding;
            
            const currentScrollHeight = chatInput.scrollHeight;
            
            if (currentScrollHeight <= minHeight) {
                chatInput.style.height = minHeight + 'px';
                chatInput.style.overflowY = 'hidden';
            } else if (currentScrollHeight >= maxHeight) {
                chatInput.style.height = maxHeight + 'px';
                chatInput.style.overflowY = 'auto';
            } else {
                chatInput.style.height = currentScrollHeight + 'px';
                chatInput.style.overflowY = 'hidden';
            }
        }

        if (chatInput) {
            chatInput.addEventListener('input', () => {
                adjustChatInputHeight();
                updateSendStopButtonState();
            });
            // Run on startup
            adjustChatInputHeight();
            updateSendStopButtonState();
        }

        function scrollToBottom() {
            messagesContainer.scrollTop = messagesContainer.scrollHeight;
        }


        // Sanitize rendered markdown/HTML before inserting into the DOM. This is the defense against
        // XSS carried in LLM output or in ingested corpus content (e.g. Confluence/uploaded docs).
        // The custom <think> tag is preserved; everything else is filtered by DOMPurify defaults.
        function sanitizeHtml(html) {
            if (!html) return '';
            if (window.DOMPurify) {
                return DOMPurify.sanitize(html, { ADD_TAGS: ['think', 'clarifying-question', 'question', 'option', 'code-execution'] });
            }
            // Sanitizer unavailable: fail closed by escaping rather than injecting raw HTML.
            return escapeHtml(String(html));
        }

        // Normalizes and balances <think> tags in the text to avoid nesting and ensure they are closed

        // Renders one message's raw text to sanitized HTML. The pipeline itself lives in
        // markdown-render.js; marked and DOMPurify are classic-script globals, injected here.
        function renderMarkdown(text, isStreaming) {
            return renderMarkdownPipeline(text, isStreaming,
                { parse: (s) => marked.parse(s), sanitize: sanitizeHtml });
        }

        // Open citation details in a modal dialog
        function toggleCitation(event, link, chunkId, documentId) {
            if (event) event.preventDefault();
            
            const dialog = document.getElementById('citation-dialog');
            const titleEl = document.getElementById('citation-dialog-title');
            const contentContainer = document.getElementById('citation-dialog-content');
            if (!dialog || !contentContainer) return;

            if (titleEl) titleEl.textContent = 'Citation Source';
            contentContainer.innerHTML = '<div class="citation-loading">Loading source content...</div>';
            dialog.showModal();

            // Fetch citation details
            const param = citationQueryParam(chunkId, documentId);
            fetch(`/document/citation?${param}`)
                .then(r => {
                    if (!r.ok) throw new Error('Failed to resolve citation details');
                    return r.text();
                })
                .then(html => {
                    contentContainer.innerHTML = html;
                    contentContainer.querySelectorAll('.citation-content-md:not([data-md-rendered])').forEach(el => {
                        el.innerHTML = renderMarkdown(el.textContent || '');
                        el.dataset.mdRendered = 'true';
                    });
                    renderMermaidDiagrams();
                })
                .catch(err => {
                    contentContainer.innerHTML = `<div class="citation-error"><strong>Error:</strong> ${escapeHtml(err.message)}</div>`;
                });
        }

        // Show thinking process in a modal dialog
        function showThinkingModal(btn) {
            const thinkingText = btn.getAttribute('data-thinking-content') || '';
            if (!thinkingText) return;
            
            const dialog = document.getElementById('citation-dialog');
            const titleEl = document.getElementById('citation-dialog-title');
            const contentContainer = document.getElementById('citation-dialog-content');
            if (dialog && contentContainer) {
                if (titleEl) titleEl.textContent = 'Thinking Process';
                contentContainer.innerHTML = `<div class="citation-content-md">${renderMarkdown(thinkingText)}</div>`;
                dialog.showModal();
            }
        }

        // Format raw citation tokens into clickable toggle links and extract thinking blocks
        function formatCitationsInElement(element) {
            if (element.getAttribute('data-status') === 'generating') {
                return;
            }
            const contentEl = element.querySelector('.message-content');
            if (!contentEl) return;
            let rawText = contentEl.getAttribute('data-raw-content') || contentEl.textContent || '';

            const isStreaming = element.classList.contains('streaming');

            // Render markdown to HTML first
            let html = renderMarkdown(rawText, isStreaming);

            // Turn citation tokens into links/badges. Runs only outside <pre>/<code>: these are
            // blunt string replacements, and applying them to code corrupted copied snippets and
            // silently rewrote mermaid diagram sources. See citation-render.js.
            html = formatCitations(html, escapeHtml);

            // Wrap tool calls in a span to allow targeting/hiding them via CSS
            html = wrapToolCalls(html);

            let hasVisibleContent = false;
            if (rawText.trim().length > 0) {
                hasVisibleContent = true;
            }

            if (isStreaming && !hasVisibleContent) {
                contentEl.innerHTML = html + syncLoaderMarkup(streamingStatusText(rawText));
            } else {
                contentEl.innerHTML = html;
            }

            // Process all think tags: replace completed ones with interactive buttons, keep streaming ones as-is
            contentEl.querySelectorAll('think').forEach((el) => {
                if (el.classList.contains('streaming-think')) {
                    return; // Keep streaming block as-is to see it stream in real time
                }

                const thinkingText = el.innerHTML;
                
                // If there is no non-whitespace, non-tool-call text outside of all think tags,
                // render the thinking process directly formatted inline instead of a button.
                const clone = contentEl.cloneNode(true);
                clone.querySelectorAll('think').forEach(t => t.remove());
                let remainingText = clone.textContent || '';
                remainingText = remainingText.replace(/🔧\s*\*?Calling\s+tool[\s\S]*?\n/g, '').trim();

                if (!isStreaming && remainingText.length === 0) {
                    el.innerHTML = renderMarkdown(thinkingText);
                    el.className = 'inline-think';
                    return;
                }

                const btnContainer = document.createElement('div');
                btnContainer.className = 'thinking-container';
                btnContainer.style.margin = '0.5rem 0';
                
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.className = 'thinking-btn';
                btn.innerHTML = '🧠 View Thinking Process';
                btn.setAttribute('data-thinking-content', thinkingText);
                btn.onclick = function() {
                    showThinkingModal(this);
                };
                
                btnContainer.appendChild(btn);
                el.replaceWith(btnContainer);
            });

            // Process all code-execution blocks: manually parse the inner markdown
            contentEl.querySelectorAll('code-execution').forEach((el) => {
                const rawMarkdown = el.innerHTML;
                el.innerHTML = renderMarkdown(rawMarkdown);
            });

            // Process all clarifying-question tags
            element.querySelectorAll('clarifying-question').forEach((el) => {
                // Parse question and option tags inside clarifying-question using DOM
                const questionEl = el.querySelector('question');
                const questionText = questionEl ? questionEl.textContent.trim() : '';
                
                const optionEls = el.querySelectorAll('option');
                const options = Array.from(optionEls).map(opt => opt.textContent.trim());

                // Check if this clarifying question is already answered.
                // We scan downstream elements in the message log to see if a user message exists after this message element.
                let hasSubsequentUserMsg = false;
                let next = element.nextElementSibling;
                let answerText = '';
                while (next) {
                    if (next.classList.contains('message-row') && next.classList.contains('message-user')) {
                        hasSubsequentUserMsg = true;
                        const userContentEl = next.querySelector('.message-content');
                        if (userContentEl) {
                            answerText = userContentEl.textContent.trim();
                        }
                        break;
                    }
                    next = next.nextElementSibling;
                }

                const cardDiv = document.createElement('div');
                cardDiv.className = 'clarifying-question-card';

                if (hasSubsequentUserMsg) {
                    cardDiv.classList.add('answered');
                    cardDiv.innerHTML = clarifyCardAnsweredMarkup(questionText, answerText);
                } else {
                    // Render active clarifying question card
                    cardDiv.innerHTML = clarifyCardActiveMarkup(questionText, options);

                    // Disable main chat inputs
                    if (chatInput) {
                        chatInput.disabled = true;
                        chatInput.placeholder = "Please respond to the clarifying question above...";
                    }
                    const micBtn = document.getElementById('voice-input-btn');
                    if (micBtn) {
                        micBtn.disabled = true;
                        micBtn.style.opacity = '0.5';
                    }
                    const submitBtn = document.getElementById('send-stop-button');
                    if (submitBtn) {
                        submitBtn.disabled = true;
                        submitBtn.style.opacity = '0.5';
                    }
                }

                el.replaceWith(cardDiv);
            });

            // Render KaTeX math
            if (window.renderMathInElement) {
                renderMathInElement(contentEl, {
                    delimiters: [
                        {left: '$$', right: '$$', display: true},
                        {left: '\\[', right: '\\]', display: true},
                        {left: '\\(', right: '\\)', display: false}
                    ],
                    throwOnError: false,
                    output: 'html'
                });
            }
        }

        function submitClarificationAnswer(el, preselectedAnswer) {
            const card = el.closest('.clarifying-question-card');
            if (!card) return;

            let answer = '';
            if (preselectedAnswer) {
                answer = preselectedAnswer;
            } else if (el.hasAttribute('data-answer')) {
                // Option chips carry their answer in an escaped data attribute (read as inert
                // data, never interpolated into an inline handler) — see SEC-02.
                answer = el.getAttribute('data-answer');
            } else {
                const textarea = card.querySelector('.question-answer-input');
                answer = textarea ? textarea.value.trim() : '';
            }

            if (!answer) return;

            // Re-enable inputs temporarily to submit the form
            if (chatInput) {
                chatInput.disabled = false;
                chatInput.value = answer;
            }

            const micBtn = document.getElementById('voice-input-btn');
            if (micBtn) {
                micBtn.disabled = false;
                micBtn.style.opacity = '1';
            }
            const submitBtn = document.getElementById('send-stop-button');
            if (submitBtn) {
                submitBtn.disabled = false;
                submitBtn.style.opacity = '1';
            }

            // Mark the card answered before submitting. updateMainInputState() re-enables the
            // composer only when no `.clarifying-question-card:not(.answered)` remains, and until a
            // reload rebuilds the card from the stored text nothing else adds the class — so without
            // this the composer stays disabled once the follow-up answer finalizes and the user has
            // to reload the page to keep talking.
            card.classList.add('answered');
            // Disable all card controls
            card.querySelectorAll('button, textarea').forEach(control => {
                control.disabled = true;
                if (control.tagName === 'BUTTON') {
                    control.style.opacity = '0.5';
                }
            });

            // Submit main form
            chatForm.requestSubmit();
        }

        function handleClarifyKeydown(event, textarea) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                const btn = textarea.nextElementSibling;
                if (btn && btn.classList.contains('btn-submit-answer')) {
                    btn.click();
                }
            }
        }


        function updateMainInputState() {
            const unanswered = document.querySelector('.clarifying-question-card:not(.answered)');
            if (!unanswered) {
                if (chatInput) {
                    chatInput.disabled = false;
                    chatInput.placeholder = chatInputDefaultPlaceholder(activePlaybookName, mcpPrompts);
                }
                const micBtn = document.getElementById('voice-input-btn');
                if (micBtn) {
                    micBtn.disabled = false;
                    micBtn.style.opacity = '1';
                }
                const submitBtn = document.getElementById('send-stop-button');
                if (submitBtn) {
                    submitBtn.disabled = false;
                    submitBtn.style.opacity = '1';
                }
            }
        }

        // Render raw mermaid diagrams into visual SVGs
        function renderMermaidDiagrams() {
            if (typeof mermaid === 'undefined') return;
            const elements = document.querySelectorAll('pre.mermaid:not([data-processed="true"])');
            if (elements.length === 0) return;
            
            elements.forEach((el, index) => {
                const id = 'mermaid-' + Date.now() + '-' + index;
                const code = el.textContent;
                el.setAttribute('data-processed', 'true');
                
                try {
                    mermaid.render(id, code).then(({ svg, bindFunctions }) => {
                        const container = el.parentElement;
                        if (container && container.classList.contains('mermaid-diagram-container')) {
                            container.innerHTML = svg;
                            if (bindFunctions) {
                                bindFunctions(container);
                            }
                        } else {
                            const div = document.createElement('div');
                            div.className = 'mermaid-diagram-container';
                            div.innerHTML = svg;
                            el.replaceWith(div);
                            if (bindFunctions) {
                                bindFunctions(div);
                            }
                        }
                    }).catch(err => {
                        console.error('Mermaid parsing error:', err);
                        const errorDiv = document.createElement('div');
                        errorDiv.className = 'mermaid-error';
                        errorDiv.textContent = '⚠️ Failed to render diagram. Click to show source code.';
                        errorDiv.style.cursor = 'pointer';
                        
                        el.style.display = 'none';
                        el.style.whiteSpace = 'pre-wrap';
                        el.style.fontFamily = 'monospace';
                        
                        errorDiv.onclick = () => {
                            el.style.display = el.style.display === 'none' ? 'block' : 'none';
                        };
                        
                        el.before(errorDiv);
                    });
                } catch (err) {
                    console.error('Mermaid render exception:', err);
                }
            });
        }

        // Initialize formatting on load
        document.addEventListener('DOMContentLoaded', function() {
            // Apply show thinking preference to body class list
            document.body.classList.toggle('hide-thinking-process', !showThinkingEnabled && !streamingEnabled);

            document.querySelectorAll('.message-assistant').forEach(function(el) {
                formatCitationsInElement(el);
            });
            renderMermaidDiagrams();
            scrollToBottom();
            updateMainInputState();
            // Without this the button paints enabled (tooltip "Send message") over an empty input
            // until the first keystroke fires the input listener — every other transition calls it.
            updateSendStopButtonState();

            // Fallback for browsers without closedby support (e.g. Safari)
            const citationDialog = document.getElementById('citation-dialog');
            if (citationDialog && !('closedBy' in HTMLDialogElement.prototype)) {
                citationDialog.addEventListener('click', (event) => {
                    if (event.target !== citationDialog) return;
                    const rect = citationDialog.getBoundingClientRect();
                    const isDialogContent = (
                        rect.top <= event.clientY &&
                        event.clientY <= rect.top + rect.height &&
                        rect.left <= event.clientX &&
                        event.clientX <= rect.left + rect.width
                    );
                    if (!isDialogContent) {
                        citationDialog.close();
                    }
                });
            }

            // Set up input and keydown event listeners for playbook autocomplete
            chatInput.addEventListener('input', function(e) {
                if (orchestratorMode) {
                    hideAutocomplete();
                    return;
                }
                const val = chatInput.value;
                const match = val.match(/^[\/\+](\S*)$/);
                if (match && mcpPrompts.length > 0) {
                    const query = match[1].toLowerCase();
                    const filtered = mcpPrompts.filter(p => p.name.toLowerCase().includes(query) || p.title.toLowerCase().includes(query));
                    if (filtered.length > 0) {
                        showAutocomplete(filtered);
                    } else {
                        hideAutocomplete();
                    }
                } else {
                    hideAutocomplete();
                }
            });

            chatInput.addEventListener('keydown', function(e) {
                const popup = document.getElementById('autocomplete-popup');
                const isPopupOpen = popup && popup.style.display === 'flex';
                
                if (isPopupOpen) {
                    if (e.key === 'ArrowDown') {
                        e.preventDefault();
                        autocompleteHighlightIndex = (autocompleteHighlightIndex + 1) % filteredAutocompletePrompts.length;
                        setHighlight(autocompleteHighlightIndex);
                        return;
                    }
                    if (e.key === 'ArrowUp') {
                        e.preventDefault();
                        autocompleteHighlightIndex = (autocompleteHighlightIndex - 1 + filteredAutocompletePrompts.length) % filteredAutocompletePrompts.length;
                        setHighlight(autocompleteHighlightIndex);
                        return;
                    }
                    if (e.key === 'Enter' || e.key === 'Tab') {
                        e.preventDefault();
                        if (filteredAutocompletePrompts[autocompleteHighlightIndex]) {
                            const p = filteredAutocompletePrompts[autocompleteHighlightIndex];
                            selectPlaybook(p.name, p.title);
                        }
                        return;
                    }
                    if (e.key === 'Escape') {
                        e.preventDefault();
                        chatInput.value = '';
                        hideAutocomplete();
                        return;
                    }
                } else {
                    // Send message on Enter (without shift key)
                    if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault();
                        if (chatInput.value.trim() !== '') {
                            chatForm.requestSubmit();
                        }
                        return;
                    }
                }

            });

            // Voice Speech-to-Text Integration
            const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
            if (SpeechRecognition) {
                const voiceGroup = document.getElementById('voice-input-group');
                const voiceBtn = document.getElementById('voice-input-btn');
                const voiceSettingsBtn = document.getElementById('voice-settings-btn');
                const voiceSettingsDropdown = document.getElementById('voice-settings-dropdown');
                const holdToRecordToggle = document.getElementById('hold-to-record-toggle');

                if (voiceGroup && voiceBtn && voiceSettingsBtn && voiceSettingsDropdown && holdToRecordToggle) {
                    voiceGroup.style.display = 'inline-flex';

                    // Initialize Speech Recognition
                    const recognition = new SpeechRecognition();
                    recognition.continuous = true;
                    recognition.interimResults = true;
                    recognition.lang = navigator.language || 'en-US';

                    let isRecording = false;
                    let finalTranscript = '';
                    let startInputValue = '';
                    let holdToRecord = localStorage.getItem('holdToRecord') === 'true';

                    // Set initial state of toggle switch
                    holdToRecordToggle.checked = holdToRecord;

                    // Save settings on toggle
                    holdToRecordToggle.addEventListener('change', function() {
                        holdToRecord = holdToRecordToggle.checked;
                        localStorage.setItem('holdToRecord', holdToRecord);
                        setupEventListeners();
                    });

                    // Dropdown visibility
                    voiceSettingsBtn.addEventListener('click', function(e) {
                        e.stopPropagation();
                        const isOpen = voiceSettingsDropdown.style.display === 'block';
                        voiceSettingsDropdown.style.display = isOpen ? 'none' : 'block';
                    });

                    // Close dropdown on outside click
                    document.addEventListener('click', function(e) {
                        if (voiceSettingsDropdown.style.display === 'block' &&
                            !voiceSettingsDropdown.contains(e.target) &&
                            e.target !== voiceSettingsBtn) {
                            voiceSettingsDropdown.style.display = 'none';
                        }
                    });

                    recognition.onstart = function() {
                        isRecording = true;
                        voiceBtn.classList.add('recording');
                        const tooltipEl = voiceBtn.querySelector('.custom-tooltip');
                        if (tooltipEl) tooltipEl.textContent = "Stop listening";
                        chatInput.placeholder = "Listening... Speak now.";
                    };

                    recognition.onend = function() {
                        isRecording = false;
                        voiceBtn.classList.remove('recording');
                        const tooltipEl = voiceBtn.querySelector('.custom-tooltip');
                        if (tooltipEl) tooltipEl.textContent = "Start voice typing";
                        chatInput.placeholder = chatInputDefaultPlaceholder(activePlaybookName, mcpPrompts);
                        adjustChatInputHeight();
                    };

                    recognition.onresult = function(event) {
                        let interimTranscript = '';
                        for (let i = event.resultIndex; i < event.results.length; ++i) {
                            if (event.results[i].isFinal) {
                                finalTranscript += event.results[i][0].transcript;
                            } else {
                                interimTranscript += event.results[i][0].transcript;
                            }
                        }
                        chatInput.value = startInputValue + finalTranscript + interimTranscript;
                        adjustChatInputHeight();
                        updateSendStopButtonState();
                    };

                    recognition.onerror = function(event) {
                        console.error("Speech recognition error", event.error);
                        if (event.error === 'not-allowed') {
                            alert("Microphone access was denied. Please allow microphone access in your browser settings.");
                        }
                    };

                    function startRecording() {
                        if (!isRecording) {
                            startInputValue = chatInput.value;
                            if (startInputValue && !startInputValue.endsWith(' ')) {
                                startInputValue += ' ';
                            }
                            finalTranscript = '';
                            recognition.start();
                        }
                    }

                    function stopRecording() {
                        if (isRecording) {
                            recognition.stop();
                        }
                    }

                    // Helper to clear existing listeners to avoid multiple attachments
                    let activeEventListeners = [];
                    function addManagedListener(element, type, handler, options) {
                        element.addEventListener(type, handler, options);
                        activeEventListeners.push({ element, type, handler });
                    }
                    function clearManagedListeners() {
                        activeEventListeners.forEach(({ element, type, handler }) => {
                            element.removeEventListener(type, handler);
                        });
                        activeEventListeners = [];
                    }

                    function setupEventListeners() {
                        clearManagedListeners();
                        stopRecording();

                        if (holdToRecord) {
                            // Hold to record event listeners
                            // Mouse
                            addManagedListener(voiceBtn, 'mousedown', function(e) {
                                e.preventDefault();
                                startRecording();
                            });
                            addManagedListener(voiceBtn, 'mouseup', function(e) {
                                e.preventDefault();
                                stopRecording();
                            });
                            addManagedListener(voiceBtn, 'mouseleave', function(e) {
                                e.preventDefault();
                                stopRecording();
                            });
                            // Touch devices
                            addManagedListener(voiceBtn, 'touchstart', function(e) {
                                e.preventDefault();
                                startRecording();
                            }, { passive: false });
                            addManagedListener(voiceBtn, 'touchend', function(e) {
                                e.preventDefault();
                                stopRecording();
                            }, { passive: false });
                            
                            // Prevent click event in hold-to-record mode to avoid conflicts
                            addManagedListener(voiceBtn, 'click', function(e) {
                                e.preventDefault();
                            });
                        } else {
                            // Tap to toggle recording
                            addManagedListener(voiceBtn, 'click', function(e) {
                                e.preventDefault();
                                if (isRecording) {
                                    stopRecording();
                                } else {
                                    startRecording();
                                }
                            });
                        }
                    }

                    // Initialize event listeners
                    setupEventListeners();
                }
            }

            // Auto-resume polling on page load if a message is generating
            const generatingBubble = document.querySelector('.message-assistant[data-status="generating"]');
            if (generatingBubble) {
                const messageId = generatingBubble.id.replace('msg-', '');
                const contentEl = generatingBubble.querySelector('.message-content');
                const loaderTextEl = generatingBubble.querySelector('.sync-loader-text');
                const intervalId = startLoaderTextRotation(loaderTextEl);

                if (chatInput) {
                    chatInput.disabled = true;
                }
                currentAbortController = new AbortController();
                updateSendStopButtonState();
                
                pollMessageStatus(messageId, generatingBubble, contentEl, intervalId);
            }
        });



        let bypassPreflight = false;

        function runPreflightValidation(content, promptName) {
            const warningEl = document.getElementById('playbook-validation-warning');
            if (warningEl) {
                const textEl = warningEl.querySelector('.warning-text');
                if (textEl) {
                    textEl.textContent = "Validating playbook selection...";
                }
                warningEl.style.display = 'flex';
            }

            chatInput.disabled = true;
            const sendStopBtn = document.getElementById('send-stop-button');
            if (sendStopBtn) {
                sendStopBtn.disabled = true;
            }

            const conversationId = CHAT_CONFIG.conversationId;
            const formData = new FormData();
            formData.append('content', content);
            if (promptName) {
                formData.append('promptName', promptName);
            }

            fetch(`/chat/${conversationId}/validate-playbook`, {
                method: 'POST',
                body: formData
            })
            .then(response => {
                if (!response.ok) {
                    throw new Error('Validation request failed with status ' + response.status);
                }
                return response.json();
            })
            .then(data => {
                // Restore warning banner
                if (warningEl) {
                    warningEl.style.display = 'none';
                    const textEl = warningEl.querySelector('.warning-text');
                    if (textEl) {
                        textEl.textContent = "A playbook must be selected before asking a question.";
                    }
                }

                if (data.plausible) {
                    bypassPreflight = true;
                    chatInput.disabled = false;
                    if (sendStopBtn) {
                        sendStopBtn.disabled = false;
                    }
                    updateSendStopButtonState();
                    chatForm.requestSubmit();
                } else {
                    showPreflightWarningCard(content, data.reason, data.suggestedPlaybookName);
                }
            })
            .catch(error => {
                console.error("Playbook validation error, falling back:", error);
                
                // Restore warning banner
                if (warningEl) {
                    warningEl.style.display = 'none';
                    const textEl = warningEl.querySelector('.warning-text');
                    if (textEl) {
                        textEl.textContent = "A playbook must be selected before asking a question.";
                    }
                }

                bypassPreflight = true;
                chatInput.disabled = false;
                if (sendStopBtn) {
                    sendStopBtn.disabled = false;
                }
                updateSendStopButtonState();
                chatForm.requestSubmit();
            });
        }

        function showPreflightWarningCard(content, reason, suggestedPlaybookName) {
            chatInput.disabled = false;
            chatInput.value = content;
            adjustChatInputHeight();
            const sendStopBtn = document.getElementById('send-stop-button');
            if (sendStopBtn) {
                sendStopBtn.disabled = false;
            }
            updateSendStopButtonState();

            const existingCard = document.getElementById('preflight-warning-card-el');
            if (existingCard) {
                existingCard.remove();
            }

            // Resolve the suggested playbook's display title, falling back to its name.
            let switchTitle = null;
            if (suggestedPlaybookName) {
                const playbookObj = mcpPrompts.find(p => p.name === suggestedPlaybookName);
                switchTitle = playbookObj ? playbookObj.title : suggestedPlaybookName;
            }
            const cardHtml = preflightCardMarkup(reason, suggestedPlaybookName, switchTitle);

            messagesContainer.insertAdjacentHTML('beforeend', cardHtml);
            scrollToBottom();
        }

        function sendAnyway() {
            const card = document.getElementById('preflight-warning-card-el');
            if (card) {
                card.remove();
            }
            bypassPreflight = true;
            chatForm.requestSubmit();
        }

        function switchAndSend(name, title) {
            const card = document.getElementById('preflight-warning-card-el');
            if (card) {
                card.remove();
            }
            selectPlaybook(name, title);
            bypassPreflight = true;
            chatForm.requestSubmit();
        }

        /**
         * One wording for a failed send, shared by both transports. These branches had drifted: the
         * streaming path mapped 403/404 to sentences while the async path — the default — showed a
         * bare "Request failed (403)."
         */

        // Intercept form submit and start streaming
        chatForm.addEventListener('submit', function(e) {
            e.preventDefault();
            if (chatInput.disabled) return;
            const content = chatInput.value.trim();
            if (!content) return;

            if (!orchestratorMode) {
                if (!activePlaybookName) {
                    const warningEl = document.getElementById('playbook-validation-warning');
                    if (warningEl) {
                        warningEl.style.display = 'flex';
                    }
                    const inputBox = document.querySelector('.chat-input-box');
                    if (inputBox) {
                        inputBox.classList.add('shake-input');
                        setTimeout(() => {
                            inputBox.classList.remove('shake-input');
                        }, 300);
                    }
                    return;
                }

                const isFirstMessage = messagesContainer.querySelectorAll('.message-row:not(#prompt-chips)').length === 0;
                const validationEnabled = CHAT_CONFIG.playbookValidationEnabled;
                if (isFirstMessage && validationEnabled && !bypassPreflight) {
                    runPreflightValidation(content, activePlaybookName);
                    return;
                }
            }

            if (bypassPreflight) {
                bypassPreflight = false;
                const card = document.getElementById('preflight-warning-card-el');
                if (card) {
                    card.remove();
                }
            }

            chatInput.value = '';
            chatInput.disabled = true;
            adjustChatInputHeight(); // Reset height

            // Create AbortController
            currentAbortController = new AbortController();
            const signal = currentAbortController.signal;

            // Update button state
            updateSendStopButtonState();

            // 1. Append User Message Bubble
            const userMsgId = 'temp-user-' + Date.now();
            const playbookTagHtml = activePlaybookName ? `<span class="playbook-tag">📋 ${promptLabelOf(activePlaybookName, mcpPrompts, PROMPT_LABELS)}</span>` : '';
            const userMsgHtml = userMessageMarkup(userMsgId, content, playbookTagHtml);
            messagesContainer.insertAdjacentHTML('beforeend', userMsgHtml);
            scrollToBottom();

            // 2. Append Assistant Message Bubble (Streaming placeholder)
            const assistantMsgId = 'temp-assistant-' + Date.now();
            const conversationId = CHAT_CONFIG.conversationId;

            const formData = new FormData();
            formData.append('content', content);
            if (activePlaybookName && !orchestratorMode) {
                formData.append('promptName', activePlaybookName);
            }

            if (!streamingEnabled || orchestratorMode) {
                // 2. Append Assistant Message Bubble (Sync/loader placeholder)
                messagesContainer.insertAdjacentHTML('beforeend',
                    assistantPlaceholderMarkup(assistantMsgId, 'sync-loading'));
                scrollToBottom();

                const assistantBubble = document.getElementById(assistantMsgId);
                const assistantContentEl = assistantBubble.querySelector('.message-content');

                const loaderTextEl = assistantBubble.querySelector('.sync-loader-text');
                const intervalId = startLoaderTextRotation(loaderTextEl);

                fetch(`/chat/${conversationId}/send-async`, {
                    method: 'POST',
                    body: formData,
                    signal: signal
                })
                .then(response => {
                    if (!response.ok) {
                        clearInterval(intervalId);
                        return response.text().then(body => {
                            const message = describeSendFailure(response.status, body);
                            assistantBubble.classList.remove('sync-loading');
                            assistantContentEl.innerHTML = `<span class="error-text">${escapeHtml(message)}</span>`;
                            currentAbortController = null;
                            updateSendStopButtonState();
                            chatInput.disabled = false;
                            chatInput.focus();
                        });
                    }
                    return response.json();
                })
                .then(data => {
                    if (!data || !data.assistantMessageId) return;
                    pollMessageStatus(data.assistantMessageId, assistantBubble, assistantContentEl, intervalId);
                })
                .catch(err => {
                    clearInterval(intervalId);
                    currentAbortController = null;
                    updateSendStopButtonState();
                    assistantBubble.classList.remove('sync-loading');
                    if (err.name === 'AbortError') {
                        finalizeStream(assistantBubble, assistantContentEl, '', null, null, null, null, null, null, true);
                    } else {
                        assistantContentEl.innerHTML = `<span class="error-text">Generation aborted: ${escapeHtml(err.message)}</span>`;
                        chatInput.disabled = false;
                        chatInput.focus();
                    }
                });
            } else {
                // 2. Append Assistant Message Bubble (Streaming placeholder)
                messagesContainer.insertAdjacentHTML('beforeend',
                    assistantPlaceholderMarkup(assistantMsgId, 'streaming'));
                scrollToBottom();

                const assistantBubble = document.getElementById(assistantMsgId);
                const assistantContentEl = assistantBubble.querySelector('.message-content');

                // 3. Initiate SSE Streaming Request via POST Fetch
                const sse = createSseAccumulator();


                fetch(`/chat/${conversationId}/send`, {
                    method: 'POST',
                    body: formData,
                    signal: signal
                })
                .then(response => {
                    if (!response.ok) {
                        return response.text().then(body => {
                            const message = describeSendFailure(response.status, body);
                            assistantBubble.classList.remove('streaming');
                            assistantContentEl.innerHTML = `<span class="error-text">${escapeHtml(message)}</span>`;
                            currentAbortController = null;
                            updateSendStopButtonState();
                            chatInput.disabled = false;
                            chatInput.focus();
                        });
                    }

                    const reader = response.body.getReader();
                    const decoder = new TextDecoder();

                    // Paints the answer as it stands. Called once per network chunk — it used to
                    // run once per SSE *line*, re-rendering the whole answer each time.
                    function paint(text) {
                        assistantContentEl.setAttribute('data-raw-content', text);
                        formatCitationsInElement(assistantBubble);
                        scrollToBottom();
                    }

                    function read() {
                        return reader.read().then(({ done, value }) => {
                            if (done) {
                                // Stream closed without a [DONE] frame.
                                finalizeStream(assistantBubble, assistantContentEl, sse.end().text);
                                return;
                            }

                            const result = sse.feed(decoder.decode(value, { stream: true }));
                            const doneEvent = result.events.find(e => e.type === 'done');

                            if (doneEvent) {
                                const d = doneEvent.payload;
                                finalizeStream(assistantBubble, assistantContentEl, result.text,
                                    d.messageId ?? null, d.promptTokens ?? null,
                                    d.completionTokens ?? null, d.totalTokens ?? null,
                                    d.cachedTokens ?? null, d.thoughtTokens ?? null);
                                return;
                            }

                            paint(result.text);
                            return read();
                        });
                    }

                    return read();
                })
                .catch(err => {
                    currentAbortController = null;
                    updateSendStopButtonState();

                    if (err.name === 'AbortError') {
                        finalizeStream(assistantBubble, assistantContentEl, sse.text, null, null, null, null, null, null, true);
                    } else {
                        assistantContentEl.innerHTML = `<span class="error-text">Generation aborted: ${escapeHtml(err.message)}</span>`;
                        chatInput.disabled = false;
                        chatInput.focus();
                    }
                });
            }
        });

        // `showCancelNotice` means "the user pressed Stop", not "this message ended badly". It was
        // once fed `status === 'error'` by the poll, which captioned every failure as a user
        // cancellation. Only a local abort sets it; a server-reported status never does, because a
        // terminal message already carries the text the server wrote for it.
        function finalizeStream(bubble, contentEl, text, messageId, promptTokens, completionTokens, totalTokens, cachedTokens, thoughtTokens, showCancelNotice) {
            bubble.classList.remove('streaming');
            bubble.classList.remove('sync-loading');
            bubble.removeAttribute('data-status');
            contentEl.setAttribute('data-raw-content', text);
            formatCitationsInElement(bubble);
            renderMermaidDiagrams();

            if (showCancelNotice) {
                if (!text.trim()) {
                    contentEl.innerHTML = cancelledNoticeMarkup(false);
                } else {
                    contentEl.innerHTML += cancelledNoticeMarkup(true);
                }
            }

            currentAbortController = null;
            updateSendStopButtonState();
            
            if (messageId) {
                bubble.id = 'msg-' + messageId;
                let metaEl = bubble.querySelector('.message-meta');
                if (!metaEl) {
                    metaEl = document.createElement('div');
                    metaEl.className = 'message-meta';
                    const bubbleInner = bubble.querySelector('.message-bubble');
                    if (bubbleInner) {
                        bubbleInner.appendChild(metaEl);
                    }
                }
                metaEl.style.display = 'block';
                
                const buttonsHtml = feedbackButtonsMarkup(messageId);
                
                const tokensHtml = tokenUsageMarkup(promptTokens, completionTokens, totalTokens,
                    cachedTokens, thoughtTokens);

                metaEl.innerHTML = buttonsHtml + tokensHtml;
                htmx.process(metaEl);
            }
            
            updateMainInputState();
            if (!chatInput.disabled) {
                chatInput.focus();
            }
            scrollToBottom();
        }

        function pollMessageStatus(messageId, bubble, contentEl, intervalId) {
            // A finished message must be finalized exactly once. This poll re-schedules itself with
            // setTimeout (below, and in the catch), and clearInterval does NOT cancel those pending
            // timers — so an orphaned one could fire seconds after the answer completed, see
            // 'done' again, and re-run finalizeStream. That rewrites metaEl.innerHTML, destroying
            // the htmx-swapped feedback widget the user is typing into.
            if (finalizedMessageIds.has(messageId)) {
                clearInterval(intervalId);
                return;
            }

            if (currentAbortController && currentAbortController.signal.aborted) {
                clearInterval(intervalId);
                finalizeStream(bubble, contentEl, '', null, null, null, null, null, null, true);
                return;
            }

            const conversationId = CHAT_CONFIG.conversationId;
            fetch('/chat/' + conversationId + '/messages/' + messageId + '/status')
                .then(response => {
                    if (!response.ok) {
                        throw new Error('HTTP status ' + response.status);
                    }
                    return response.json();
                })
                .then(data => {
                    if (currentAbortController && currentAbortController.signal.aborted) {
                        clearInterval(intervalId);
                        finalizeStream(bubble, contentEl, '', null, null, null, null, null, null, true);
                        return;
                    }

                    // Reaching here is positive proof the user did NOT stop: all three
                    // `signal.aborted` guards return before it. So the notice is off — whatever
                    // the server says happened, it authored the message text itself.
                    const decision = pollTerminalDecision(data.status, false);
                    if (!decision.terminal) {
                        setTimeout(() => pollMessageStatus(messageId, bubble, contentEl, intervalId), 3000);
                        return;
                    }
                    clearInterval(intervalId);
                    finalizedMessageIds.add(messageId);
                    // Defaulted because a terminal status is now anything but 'generating': a
                    // payload without a message would otherwise throw into .catch below, which
                    // re-arms the poll — the same endless spin this branch exists to prevent.
                    const msg = data.message || {};
                    const t = pickTokenCounts(msg);
                    finalizeStream(bubble, contentEl, msg.content || '', msg.id, t.promptTokens,
                        t.completionTokens, t.totalTokens, t.cachedTokens, t.thoughtTokens,
                        decision.showCancelNotice);
                })
                .catch(err => {
                    if (currentAbortController && currentAbortController.signal.aborted) {
                        clearInterval(intervalId);
                        finalizeStream(bubble, contentEl, '', null, null, null, null, null, null, true);
                        return;
                    }
                    setTimeout(() => pollMessageStatus(messageId, bubble, contentEl, intervalId), 3000);
                });
        }
