/*
 * Tests for the admin JSONPath filter DSL.
 *
 * The governing property is ROUND-TRIP STABILITY: build(parse(build(tree))) must equal build(tree).
 * An admin saves a filter, reopens it, and the UI parses the stored string back into the builder —
 * so any disagreement between the two halves silently rewrites the query. Nothing errors; the
 * results are just wrong.
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import { buildJsonPath, getFieldType, parseJsonPath }
    from '../../main/resources/static/js/admin/jsonpath-filter.js';

const SCHEMA = {
    properties: {
        category: { type: 'string' },
        via: { type: 'string' },
        count: { type: 'integer' },
        active: { type: 'boolean' },
        nested: { properties: { inner: { type: 'string' } } },
        rows: { items: { properties: { label: { type: 'string' } } } },
    },
};

const rule = (field, operator, value) => ({ type: 'rule', field, operator, value });
const group = (logic, ...children) => ({ type: 'group', logic, children });

/** build → parse → build; stable output means the two halves agree. */
function roundTrip(tree, schema = SCHEMA) {
    const once = buildJsonPath(tree, schema);
    const twice = buildJsonPath(parseJsonPath(once), schema);
    return { once, twice };
}

describe('getFieldType', () => {
    test('reads a top-level declared type', () => {
        assert.equal(getFieldType(SCHEMA, 'category'), 'string');
        assert.equal(getFieldType(SCHEMA, 'count'), 'integer');
    });

    test('descends into nested properties', () => {
        assert.equal(getFieldType(SCHEMA, 'nested.inner'), 'string');
    });

    test('descends through array items', () => {
        assert.equal(getFieldType(SCHEMA, 'rows.label'), 'string');
    });

    test('returns null for an undeclared field', () => {
        assert.equal(getFieldType(SCHEMA, 'nope'), null);
        assert.equal(getFieldType(SCHEMA, 'nested.nope'), null);
    });

    test('returns null with no schema at all', () => {
        assert.equal(getFieldType(null, 'category'), null);
        assert.equal(getFieldType({}, 'category'), null);
    });
});

describe('buildJsonPath quoting', () => {
    test('quotes a declared string', () => {
        assert.equal(buildJsonPath(group('&&', rule('category', '==', 'schema')), SCHEMA),
            '@.category == "schema"');
    });

    test('leaves declared numerics and booleans unquoted', () => {
        assert.equal(buildJsonPath(group('&&', rule('count', '>', '5')), SCHEMA),
            '@.count > 5');
        assert.equal(buildJsonPath(group('&&', rule('active', '==', 'true')), SCHEMA),
            '@.active == true');
    });

    test('falls back to the value shape when the field is undeclared', () => {
        // An undeclared numeric-looking value stays unquoted; text gets quoted.
        assert.equal(buildJsonPath(group('&&', rule('unknown', '==', '42')), SCHEMA),
            '@.unknown == 42');
        assert.equal(buildJsonPath(group('&&', rule('unknown', '==', 'abc')), SCHEMA),
            '@.unknown == "abc"');
    });

    test('does not double-quote a value the user already quoted', () => {
        assert.equal(buildJsonPath(group('&&', rule('category', '==', '"schema"')), SCHEMA),
            '@.category == "schema"');
    });

    test('escapes an embedded quote', () => {
        assert.equal(buildJsonPath(group('&&', rule('category', '==', 'a"b')), SCHEMA),
            '@.category == "a\\"b"');
    });
});

describe('operators', () => {
    test('contains becomes like_regex with the case-insensitive flag', () => {
        assert.equal(buildJsonPath(group('&&', rule('category', 'contains', 'sch')), SCHEMA),
            '@.category like_regex "sch" flag "i"');
    });

    test('starts with is emitted verbatim', () => {
        assert.equal(buildJsonPath(group('&&', rule('category', 'starts with', 'sch')), SCHEMA),
            '@.category starts with "sch"');
    });

    test('like_regex + flag round-trips back to contains', () => {
        const parsed = parseJsonPath('@.category like_regex "sch" flag "i"');
        assert.equal(parsed.children[0].operator, 'contains');
        assert.equal(parsed.children[0].value, 'sch');
    });

    test('comparison operators survive parsing', () => {
        for (const op of ['==', '!=', '>', '<']) {
            const parsed = parseJsonPath(`@.count ${op} 5`);
            assert.equal(parsed.children[0].operator, op, `operator ${op}`);
            assert.equal(parsed.children[0].value, '5');
        }
    });
});

describe('round-trip stability', () => {
    test('a simple rule is stable', () => {
        const { once, twice } = roundTrip(group('&&', rule('category', '==', 'schema')));
        assert.equal(twice, once);
    });

    test('a value containing a quote is stable', () => {
        // The parser's escape handling was dead: `expr[pos] === '\\\\'` compared one character to
        // a TWO-character string, so it never fired. `"a\"b"` parsed as `a\` and the rest was
        // dropped — the admin's saved filter silently became a different query.
        const { once, twice } = roundTrip(group('&&', rule('category', '==', 'a"b')));
        assert.equal(once, '@.category == "a\\"b"');
        assert.equal(twice, once, 'round trip lost the escaped quote');
        assert.equal(parseJsonPath(once).children[0].value, 'a"b');
    });

    test('a value containing spaces is stable', () => {
        // skipWhitespace used /\\s/ — a literal backslash followed by "s", never whitespace.
        const { once, twice } = roundTrip(group('&&', rule('category', '==', 'two words')));
        assert.equal(parseJsonPath(once).children[0].value, 'two words');
        assert.equal(twice, once);
    });

    test('a value containing a backslash is stable', () => {
        const { twice, once } = roundTrip(group('&&', rule('category', '==', 'a\\b')));
        assert.equal(twice, once);
    });

    test('multiple rules with && are stable', () => {
        const { once, twice } = roundTrip(group('&&',
            rule('category', '==', 'schema'), rule('count', '>', '5')));
        assert.equal(once, '@.category == "schema" && @.count > 5');
        assert.equal(twice, once);
    });

    test('|| logic is preserved', () => {
        const { once, twice } = roundTrip(group('||',
            rule('category', '==', 'a'), rule('via', '==', 'b')));
        assert.ok(once.includes('||'), once);
        assert.equal(twice, once);
    });

    test('a nested group is stable', () => {
        const { once, twice } = roundTrip(group('&&',
            rule('category', '==', 'schema'),
            group('||', rule('via', '==', 'x'), rule('via', '==', 'y'))));
        assert.ok(once.includes('('), once);
        assert.equal(twice, once);
    });

    test('contains round-trips', () => {
        const { once, twice } = roundTrip(group('&&', rule('category', 'contains', 'sch')));
        assert.equal(twice, once);
    });

    test('every operator round-trips', () => {
        for (const op of ['==', '!=', 'contains', 'starts with']) {
            const { once, twice } = roundTrip(group('&&', rule('category', op, 'val')));
            assert.equal(twice, once, `operator ${op} did not round-trip`);
        }
    });
});

describe('edge cases', () => {
    test('an empty group serialises to an empty string', () => {
        assert.equal(buildJsonPath(group('&&'), SCHEMA), '');
        assert.equal(buildJsonPath(null, SCHEMA), '');
    });

    test('an empty expression parses to an empty group', () => {
        const parsed = parseJsonPath('');
        assert.equal(parsed.type, 'group');
        assert.deepEqual(parsed.children, []);
    });

    test('an empty child group is skipped rather than emitting ()', () => {
        const out = buildJsonPath(group('&&', rule('category', '==', 'a'), group('&&')), SCHEMA);
        assert.ok(!out.includes('()'), out);
    });

    test('a single wrapped group is unwrapped on parse', () => {
        const parsed = parseJsonPath('(@.category == "a" && @.via == "b")');
        assert.equal(parsed.type, 'group');
        assert.equal(parsed.children.length, 2);
    });

    test('field paths with dots survive', () => {
        const { once, twice } = roundTrip(group('&&', rule('nested.inner', '==', 'v')));
        assert.equal(parseJsonPath(once).children[0].field, 'nested.inner');
        assert.equal(twice, once);
    });
});
