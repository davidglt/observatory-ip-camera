// Copyright (C) 2026 David González López-Tercero
// SPDX-License-Identifier: GPL-3.0-or-later
// See LICENSE in the repository root for licensing details.
// Exercises the extracted telemetry renderer, not a real browser.

'use strict';
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const code = fs.readFileSync(process.argv[2], 'utf8');

function element() {
    return {
        children: [], dataset: {}, style: {}, textContent: '', hidden: false,
        append(...items) { this.children.push(...items); },
        replaceChildren(...items) { this.children = items; this.textContent = ''; }
    };
}

const current = 'app_cpu_current_normalized_percent';
const average = 'app_cpu_recent_average_normalized_percent';
const count = 'app_cpu_logical_count';
const state = 'app_cpu_normalized_state';
const stale = 'app_cpu_normalized_stale';
const cases = [
    ['available', {}, ['25.00 %', '20.00 %']],
    ['zero', {[current]:0, [average]:0}, ['0.00 %', '0.00 %']],
    ['above-100', {[current]:125, [average]:112.5}, ['125.00 %', '112.50 %']],
    ['collecting', {[current]:null, [average]:null, [state]:'collecting'},
        ['Collecting', 'Collecting']],
    ['unavailable', {[current]:null, [average]:null, [state]:'unavailable'},
        ['Unavailable', 'Unavailable']],
    ['missing', {[current]:undefined, [average]:undefined},
        ['Unavailable', 'Unavailable']],
    ['partial-average', {[current]:null}, ['Unavailable', '20.00 %']],
    ['partial-current', {[average]:null}, ['25.00 %', 'Unavailable']],
    ['stale-flag', {[stale]:true}, ['25.00 % (stale)', '20.00 % (stale)']],
    ['stale-state', {[state]:'stale'}, ['25.00 % (stale)', '20.00 % (stale)']],
    ['stale-partial-average', {[current]:null, [stale]:true},
        ['Unavailable', '20.00 % (stale)']],
    ['stale-partial-current', {[average]:null, [state]:'stale'},
        ['25.00 % (stale)', 'Unavailable']],
    ['nonfinite', {[current]:NaN, [average]:Infinity},
        ['Unavailable', 'Unavailable']],
    ['numeric-strings', {[current]:'25', [average]:'20'},
        ['Unavailable', 'Unavailable']]
];
for (const invalid of [null, undefined, 0, -1, 1.5, '8']) {
    cases.push(['invalid-count-' + String(invalid), {[count]:invalid},
        ['Unavailable', 'Unavailable'], false]);
}

function allRows(node) {
    return [
        ...(node.className === 'telemetry-row' ? [node] : []),
        ...node.children.flatMap(allRows)
    ];
}
function normalize(value) {
    return value.replace(/\s+/g, ' ').trim();
}

cases.push(
    ['string-false-flag', {[stale]:'false'}, ['25.00 %', '20.00 %']],
    ['string-true-flag', {[stale]:'true'}, ['25.00 %', '20.00 %']]
);

(async () => {
    let failures = 0;
    for (const [name, overrides, expected, validCount = true] of cases) {
        try {
            const elements = new Map();
            const get = id => {
                if (!elements.has(id)) elements.set(id, element());
                return elements.get(id);
            };
            const data = {
                panel_enabled:true, panel_device:true,
                panel_environment:false, panel_capture:false, panel_server:false,
                [current]:25, [average]:20, [count]:8,
                [stale]:false, [state]:'available', ...overrides
            };
            const context = {
                $:get,
                document:{createElement:element},
                window:{addEventListener() {}},
                requestAnimationFrame() {},
                fetch:async () => ({ok:true, json:async () => data})
            };
            vm.createContext(context);
            vm.runInContext(code, context, {timeout:2000});
            await context.updateTelemetry();

            const rows = allRows(get('telemetry'));
            function valueFor(pattern) {
                const matches = rows.filter(
                    row => pattern.test(row.children[0].textContent)
                );
                assert.strictEqual(matches.length, 1, 'Expected one matching CPU row');
                return normalize(matches[0].children[1].textContent);
            }
            const actualCurrent = valueFor(/current CPU|CPU current/i);
            const actualAverage = valueFor(/CPU.*average|average.*CPU/i);
            const actualCount = valueFor(/logical CPU count|CPU count used/i);

            assert.strictEqual(actualCurrent, expected[0], 'current CPU');
            assert.strictEqual(actualAverage, expected[1], 'average CPU');
            assert.strictEqual(actualCount, validCount ? '8' : 'Unavailable', 'CPU count');
            console.log('PASS:', name);
        } catch (error) {
            failures++;
            console.log('FAIL:', name, '-', error.message);
        }
    }
    console.log(JSON.stringify({cases:cases.length, failures}));
    process.exitCode = failures ? 1 : 0;
})().catch(error => {
    console.error(error);
    process.exitCode = 2;
});
