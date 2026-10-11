/*
 * Dynamic area filtering and prompt preservation for the playbook admin page.
 *
 * In the playbook admin modal/form:
 * 1. Filtering system prompts to those matching the selected area (or global prompts with no area).
 * 2. Preserving an existing prompt when editing an existing playbook whose prompt was assigned
 *    to another area or moved later, annotating it as "(area: <area>)" when out-of-area.
 * 3. Cleaning up the "(area: <area>)" annotation when the prompt is in-area (e.g. after the area
 *    is changed to match, or when editing an in-area playbook).
 * 4. Disabling and clearing the prompt picker for roles (orchestrator, reviewer) that run
 *    under self-contained instructions.
 */

/**
 * Filters the options in the systemPrompt select according to the currently chosen area.
 * Preserves `preserveValue` if provided, even if it belongs to a different area.
 * Reverts any out-of-area annotation once an option matches the area.
 *
 * @param {HTMLSelectElement} select The system prompt <select> element.
 * @param {string} selectedArea The currently selected area name.
 * @param {string} [preserveValue] An optional prompt name to preserve even if cross-area.
 */
export function filterSystemPromptsForArea(select, selectedArea, preserveValue) {
    if (!select || !select.options) return;
    const currentArea = (selectedArea || '').trim().toLowerCase();

    for (let i = 0; i < select.options.length; i++) {
        const opt = select.options[i];
        if (!opt.value || opt.dataset.dynamic === 'true') {
            opt.hidden = false;
            opt.disabled = false;
            continue;
        }

        if (!opt.dataset.originalText) {
            opt.dataset.originalText = opt.textContent;
        }
        const baseText = opt.dataset.originalText;
        const optArea = (opt.getAttribute('data-area') || '').trim().toLowerCase();
        const isPreserved = Boolean(preserveValue && opt.value === preserveValue);
        const matches = !optArea || !currentArea || optArea === currentArea;

        opt.hidden = !matches && !isPreserved;
        opt.disabled = !matches && !isPreserved;

        if (isPreserved && !matches) {
            opt.textContent = `${baseText} (area: ${opt.getAttribute('data-area') || 'none'})`;
            opt.dataset.annotated = 'true';
        } else {
            opt.textContent = baseText;
            delete opt.dataset.annotated;
        }
    }

    if (select.selectedIndex >= 0 && select.options[select.selectedIndex].disabled) {
        select.value = '';
    }
}

/**
 * Sets the system prompt select value and state on the edit form.
 * Injects a dynamic option if the value is not present in the pre-rendered list.
 *
 * @param {HTMLSelectElement} select The system prompt <select> element.
 * @param {string} value The system prompt name to apply.
 * @param {string} areaValue The current area name.
 * @param {boolean} isReadOnly Whether the form is in read-only mode.
 */
export function applySystemPrompt(select, value, areaValue, isReadOnly) {
    if (!select || !select.options) return;

    for (let i = select.options.length - 1; i >= 0; i--) {
        const opt = select.options[i];
        if (opt.dataset?.dynamic === 'true') {
            opt.remove();
        }
    }

    if (value && !Array.from(select.options).some(o => o.value === value)) {
        const doc = select.ownerDocument || document;
        const opt = doc.createElement('option');
        opt.value = value;
        opt.textContent = `${value} (inactive / not CHAT)`;
        opt.dataset.dynamic = 'true';
        select.appendChild(opt);
    }

    select.value = value || '';
    select.disabled = Boolean(isReadOnly);
    filterSystemPromptsForArea(select, areaValue, value);
    if (select.selectedIndex >= 0) {
        select.options[select.selectedIndex].hidden = false;
        select.options[select.selectedIndex].disabled = false;
    }
}

/**
 * Returns whether a role is considered a specialist (all roles except orchestrator and reviewer).
 *
 * @param {string} role The role name.
 * @returns {boolean} True if the role is a specialist.
 */
export function isSpecialistRole(role) {
    if (!role || (typeof role === 'string' && role.trim() === '')) return true;
    const r = role.toLowerCase();
    return r !== 'orchestrator' && r !== 'reviewer';
}

/**
 * Updates system prompt select enabled/disabled state based on the selected target agent role.
 * Orchestrator and reviewer playbooks run self-contained instructions, so their system prompt
 * input is disabled and cleared.
 *
 * @param {HTMLSelectElement} sysPromptSelect The system prompt <select> element.
 * @param {string} targetAgent The selected role ('specialist', 'orchestrator', 'reviewer').
 * @param {boolean} isReadOnly Whether the form is in read-only mode.
 */
export function updateRoleState(sysPromptSelect, targetAgent, isReadOnly) {
    if (!sysPromptSelect) return;
    if (!isSpecialistRole(targetAgent)) {
        sysPromptSelect.value = '';
        sysPromptSelect.disabled = true;
    } else if (!isReadOnly) {
        sysPromptSelect.disabled = false;
    }
}
