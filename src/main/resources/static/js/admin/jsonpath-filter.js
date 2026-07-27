/*
 * The JSONPath filter DSL used by the admin JSON-objects browser.
 *
 * Two halves of one contract: buildJsonPath() serialises a filter tree to a JSONPath predicate
 * string, parseJsonPath() reads that string back into a tree. The round trip runs every time an
 * admin reopens a saved filter, so a disagreement between them silently rewrites the filter — the
 * user sees a different query than the one they saved, with no error.
 *
 * Extracted from templates/admin/json-objects.html so both halves can be tested against each other.
 * The template's `schema` was module-level mutable state populated from a Thymeleaf-injected value;
 * here it is a parameter.
 */

/**
 * Looks up a field's declared JSON-schema type, descending through `properties` and array `items`.
 * Returns null when the path is not in the schema — callers then fall back to guessing from the
 * value's shape.
 */
export function getFieldType(schema, fieldPath) {
    if (!schema) return null;
    const parts = String(fieldPath).split('.');
    let current = schema;
    for (let i = 0; i < parts.length; i++) {
        if (current.properties && current.properties[parts[i]]) {
            current = current.properties[parts[i]];
        } else if (current.items && current.items.properties
            && current.items.properties[parts[i]]) {
            current = current.items.properties[parts[i]];
        } else {
            return null;
        }
    }
    return current.type;
}

/** Serialises a filter tree into a JSONPath predicate string. */
export function buildJsonPath(groupData, schema) {
    if (!groupData || !groupData.children || groupData.children.length === 0) return '';

    const parts = [];
    for (const child of groupData.children) {
        if (child.type === 'group') {
            const childStr = buildJsonPath(child, schema);
            if (childStr) {
                parts.push(`(${childStr})`);
            }
        } else if (child.type === 'rule') {
            let valStr = child.value;
            const fieldType = getFieldType(schema, child.field);
            let shouldQuote = false;

            if (fieldType === 'string') {
                shouldQuote = true;
            } else if (fieldType === 'integer' || fieldType === 'number'
                || fieldType === 'boolean') {
                shouldQuote = false;
            } else {
                // No schema entry: infer from the value's shape.
                if (child.value !== 'true' && child.value !== 'false'
                    && isNaN(Number(child.value))) {
                    shouldQuote = true;
                }
            }

            if (shouldQuote) {
                if (!(valStr.startsWith('"') && valStr.endsWith('"'))) {
                    // Backslashes first, then quotes — reversing the order would re-escape the
                    // backslashes just introduced. The writer must escape everything the reader
                    // treats as an escape, or a value containing "\" comes back short a character.
                    valStr = '"' + valStr.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
                }
            }

            if (child.operator === 'contains') {
                parts.push(`@.${child.field} like_regex ${valStr} flag "i"`);
            } else if (child.operator === 'starts with') {
                parts.push(`@.${child.field} starts with ${valStr}`);
            } else {
                parts.push(`@.${child.field} ${child.operator} ${valStr}`);
            }
        }
    }

    return parts.join(` ${groupData.logic} `);
}

/** Parses a JSONPath predicate string back into a filter tree. */
export function parseJsonPath(expr) {
    if (!expr) return { type: 'group', logic: '&&', children: [] };
    let pos = 0;

    function skipWhitespace() {
        while (pos < expr.length && /\s/.test(expr[pos])) pos++;
    }

    function parseString() {
        if (expr[pos] !== '"' && expr[pos] !== "'") return null;
        const quote = expr[pos++];
        let res = '';
        while (pos < expr.length) {
            if (expr[pos] === '\\') {
                // Backslash escape: take the next character literally, so an escaped quote
                // inside a value does not terminate the string.
                pos++;
                res += expr[pos++];
            } else if (expr[pos] === quote) {
                pos++;
                break;
            } else {
                res += expr[pos++];
            }
        }
        return res;
    }

    function parseToken() {
        skipWhitespace();
        if (pos >= expr.length) return null;

        if (expr[pos] === '(' || expr[pos] === ')') {
            return expr[pos++];
        }

        if (expr[pos] === '"' || expr[pos] === "'") {
            return `"${parseString()}"`;
        }

        const two = expr.substr(pos, 2);
        if (two === '&&' || two === '||' || two === '==' || two === '!=') {
            pos += 2;
            return two;
        }

        if (expr[pos] === '>' || expr[pos] === '<') {
            return expr[pos++];
        }

        const match = expr.substr(pos).match(/^[a-zA-Z0-9_@.\[\]\-]+/);
        if (match) {
            pos += match[0].length;
            return match[0];
        }

        pos++;
        return null;
    }

    const tokens = [];
    while (pos < expr.length) {
        skipWhitespace();
        if (pos >= expr.length) break;

        if (expr.substr(pos).startsWith('like_regex')) {
            tokens.push('like_regex');
            pos += 'like_regex'.length;
            continue;
        }
        if (expr.substr(pos).startsWith('flag "i"')) {
            tokens.push('flag "i"');
            pos += 'flag "i"'.length;
            continue;
        }
        if (expr.substr(pos).startsWith('starts with')) {
            tokens.push('starts with');
            pos += 'starts with'.length;
            continue;
        }

        const t = parseToken();
        if (t) tokens.push(t);
    }

    let tPos = 0;

    function parseExpression() {
        let logic = '&&';
        const children = [];

        while (tPos < tokens.length && tokens[tPos] !== ')') {
            const term = parseTerm();
            if (term) {
                children.push(term);
            }

            if (tPos < tokens.length && (tokens[tPos] === '&&' || tokens[tPos] === '||')) {
                logic = tokens[tPos];
                tPos++;
            } else if (tPos < tokens.length && tokens[tPos] !== ')') {
                break;
            }
        }

        if (children.length === 1 && children[0].type === 'group') {
            return children[0];
        }
        return { type: 'group', logic, children };
    }

    function parseTerm() {
        if (tPos >= tokens.length) return null;

        if (tokens[tPos] === '(') {
            tPos++;
            const exp = parseExpression();
            if (tPos < tokens.length && tokens[tPos] === ')') {
                tPos++;
            }
            return exp;
        }

        if (tokens[tPos].startsWith('@.')) {
            const field = tokens[tPos].substring(2);
            tPos++;

            if (tPos < tokens.length) {
                let operator = tokens[tPos];
                tPos++;

                let value = '';
                if (tPos < tokens.length) {
                    value = tokens[tPos];
                    tPos++;

                    if (value.startsWith('"') && value.endsWith('"')) {
                        value = value.substring(1, value.length - 1);
                    }
                }

                if (operator === 'like_regex' && tPos < tokens.length
                    && tokens[tPos] === 'flag "i"') {
                    operator = 'contains';
                    tPos++;
                }

                return { type: 'rule', field, operator, value };
            }
        }

        return null;
    }

    return parseExpression();
}
