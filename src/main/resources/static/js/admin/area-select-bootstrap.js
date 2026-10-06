/*
 * Exposes area matching to the classic inline scripts of the playbook, prompt and orchestrator
 * admin pages, which call it from click handlers (Edit, Load), never at load time.
 */

import { matchArea } from './area-select.js';

window.AreaSelect = { matchArea };
