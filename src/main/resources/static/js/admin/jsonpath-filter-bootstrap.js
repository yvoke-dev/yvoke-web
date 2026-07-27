/*
 * Exposes the JSONPath filter DSL to the classic inline script in admin/json-objects.html.
 *
 * That page cannot become a module: its <template> rows are cloned at runtime and carry inline
 * on*= handlers (addGroupRule, updateRuleValueInput, …) that resolve against the global scope, so
 * converting the script would break every dynamically added filter row. A global assignment from a
 * module script is the smallest bridge that keeps the DSL testable without touching the handlers.
 *
 * Safe despite module scripts being deferred: every call site in that page sits inside a
 * user-triggered handler, never at load time.
 */

import { buildJsonPath, getFieldType, parseJsonPath } from './jsonpath-filter.js';

window.JsonPathFilter = {
    build: buildJsonPath,
    parse: parseJsonPath,
    getFieldType: getFieldType,
};
