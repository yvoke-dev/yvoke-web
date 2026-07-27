/*
 * Syntax gate for every piece of browser-side JavaScript in the project.
 *
 * Nothing else checks this. Spotless is Java-only, so a syntax error in a static module or in a
 * Thymeleaf template's inline <script> compiles green all the way through `./mvnw verify` — the
 * only job that would notice is the browser e2e suite, and it does not cover every page. This test
 * parses each file with `node --check`, which is a parse, not an execution: no DOM is needed and no
 * module side effects run (the bootstrap files assign to `window`, which does not exist here).
 *
 * Template scripts are checked as classic scripts, because that is what the browser treats them as.
 * Thymeleaf's inline expressions use the `/*[[${...}]]* /` comment form, which is valid JavaScript,
 * so the extracted body parses standalone.
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const STATIC_JS = 'src/main/resources/static/js';
const TEMPLATES = 'src/main/resources/templates';

/** All app-owned .js under a directory. Vendored bundles are third-party and excluded. */
function appScripts(dir) {
    return fs.readdirSync(dir, { recursive: true })
        .map(String)
        .filter(f => f.endsWith('.js') && !f.endsWith('.min.js'))
        .map(f => path.join(dir, f))
        .filter(f => fs.statSync(f).isFile())
        .sort();
}

function templates(dir) {
    return fs.readdirSync(dir, { recursive: true })
        .map(String)
        .filter(f => f.endsWith('.html'))
        .map(f => path.join(dir, f))
        .sort();
}

/**
 * Inline <script> bodies in an HTML file: those with a src/th:src are external, and a non-JS
 * `type` (e.g. application/json) is data rather than code.
 */
function inlineScripts(html) {
    const out = [];
    const re = /<script([^>]*)>([\s\S]*?)<\/script>/gi;
    let m;
    while ((m = re.exec(html)) !== null) {
        const attrs = m[1];
        const body = m[2];
        if (/\bsrc\s*=|\bth:src\s*=/i.test(attrs)) continue;
        const type = /\btype\s*=\s*["']([^"']+)["']/i.exec(attrs);
        if (type && !/javascript|module/i.test(type[1])) continue;
        if (body.trim() === '') continue;
        out.push({ body, isModule: /\btype\s*=\s*["']module["']/i.test(attrs) });
    }
    return out;
}

/** Runs `node --check`, returning null when it parses or the parser's message when it does not. */
function syntaxError(file) {
    try {
        execFileSync(process.execPath, ['--check', file], { stdio: 'pipe' });
        return null;
    } catch (e) {
        return String(e.stderr || e.message).split('\n').slice(0, 6).join('\n');
    }
}

describe('static JavaScript parses', () => {
    const files = appScripts(STATIC_JS);

    test('the walk actually finds the modules', () => {
        // A walk that finds nothing would make every assertion below vacuously pass.
        assert.ok(files.length >= 9, `expected the extracted modules, found ${files.length}`);
    });

    for (const file of files) {
        test(file.replace(STATIC_JS + '/', ''), () => {
            assert.equal(syntaxError(file), null);
        });
    }
});

describe('inline template scripts parse', () => {
    const found = [];
    for (const file of templates(TEMPLATES)) {
        const scripts = inlineScripts(fs.readFileSync(file, 'utf8'));
        scripts.forEach((s, i) => found.push({ file, index: i, ...s }));
    }

    test('the walk actually finds inline scripts', () => {
        assert.ok(found.length >= 10, `expected inline scripts, found ${found.length}`);
    });

    for (const s of found) {
        const label = `${s.file.replace(TEMPLATES + '/', '')} [script ${s.index}]`;
        test(label, () => {
            // Written outside the project so the root package.json's "type": "module" does not
            // apply — a classic <script> is a script, and must parse as one.
            const tmp = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'yvoke-syntax-')),
                s.isModule ? 'probe.mjs' : 'probe.js');
            fs.writeFileSync(tmp, s.body);
            try {
                assert.equal(syntaxError(tmp), null);
            } finally {
                fs.rmSync(path.dirname(tmp), { recursive: true, force: true });
            }
        });
    }
});
