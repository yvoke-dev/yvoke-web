/*
 * Exposes playbook system prompt helpers to the classic inline scripts in admin/playbooks.html.
 */

import { filterSystemPromptsForArea, applySystemPrompt, updateRoleState, isSpecialistRole } from './playbook-prompt.js';

window.PlaybookPrompt = { filterSystemPromptsForArea, applySystemPrompt, updateRoleState, isSpecialistRole };
