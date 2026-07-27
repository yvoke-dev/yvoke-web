/*
 * Exposes the date-preset calculation to the classic inline script in chat/admin/cost-monitoring.html,
 * whose <select> uses an inline onchange= handler that must resolve globally.
 */

import { presetRange, toLocalIsoDate } from './date-presets.js';

window.DatePresets = { presetRange, toLocalIsoDate };
