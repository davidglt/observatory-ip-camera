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

const cases = [
  ['zero', '0', '0.0 KB'],
  ['KB', '976', '999.4 KB'],
  ['MB', '977', '1.0 MB'],
  ['below-GB', '976513', '999.9 MB'],
  ['GB', '976514', '1.0 GB'],
  ['GiB-to-decimal', '1048576', '1.1 GB'],
  ['collecting', 'Collecting', 'Collecting'],
  ['unavailable', 'Unavailable', 'Unavailable'],
  ['stale', '1000 (stale)', '1.0 MB (stale)'],
  ['zero-stale', '0 (stale)', '0.0 KB (stale)'],
  ['null', null, 'Unavailable'],
  ['missing', undefined, 'Unavailable'],
  ['empty', '', 'Unavailable'],
  ['spaces', '   ', 'Unavailable'],
  ['boolean', false, 'Unavailable'],
  ['negative', '-1', 'Unavailable'],
  ['NaN-string', 'NaN', 'Unavailable'],
  ['infinity-string', 'Infinity', 'Unavailable'],
  ['unit-bearing', '1000 KB', 'Unavailable'],
  ['trailing-text', '1000garbage', 'Unavailable'],
  ['exponent-text', '1e3', 'Unavailable'],
  ['extra-decimal', '1.2.3', 'Unavailable'],
  ['malformed-stale', '1000garbage (stale)', 'Unavailable'],
  ['conversion-overflow', '179' + '0'.repeat(306), 'Unavailable']
];

const memoryFields = [
    { label: "PSS:", value: "app_pss_kb" },
    { label: "PSS peak:", value: "app_pss_peak_kb" },
    { label: "Available RAM:", value: "system_available_ram_kb" },
    { label: "Total RAM:", value: "system_total_ram_kb" }
];

function allRows(node) {
    return [
        ...(node.className === 'telemetry-row' ? [node] : []),
        ...node.children.flatMap(allRows)
    ];
}
function normalize(value) {
    return value.replace(/\s+/g, ' ').trim();
}

(async () => {
    let failures = 0;
    for (const [name, input, expected] of cases) {
        try {
            const elements = new Map();
            const get = id => {
                if (!elements.has(id)) elements.set(id, element());
                return elements.get(id);
            };
            const data = {
                app_cpu_current_normalized_percent:25,
                app_cpu_recent_average_normalized_percent:20,
                app_cpu_logical_count:8,
                app_cpu_normalized_stale:false,
                app_cpu_normalized_state:'available',
                panel_enabled:true, panel_device:true,
                panel_environment:false, panel_capture:false, panel_server:false,
                app_pss_kb: input,
                app_pss_peak_kb: input,
                system_available_ram_kb: input,
                system_total_ram_kb: input
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

            // Verify exactly one occurrence of each label
            for (const field of memoryFields) {
                const matches = rows.filter(
                    row => row.children[0].textContent === field.label
                );
                assert.strictEqual(matches.length, 1, `Expected one matching row for ${field.label}`);

                const actualValue = normalize(matches[0].children[1].textContent);
                assert.strictEqual(actualValue, expected, `${field.label} value mismatch`);
            }

            console.log('PASS:', name);
        } catch (error) {
            failures++;
            console.log('FAIL:', name, '-', error.message);
        }
    }

    // Test mixed distinct readings
    try {
        const elements = new Map();
        const get = id => {
            if (!elements.has(id)) elements.set(id, element());
            return elements.get(id);
        };
        const data = {
            app_cpu_current_normalized_percent:25,
                app_cpu_recent_average_normalized_percent:20,
                app_cpu_logical_count:8,
                app_cpu_normalized_stale:false,
                app_cpu_normalized_state:'available',
                panel_enabled:true, panel_device:true,
            panel_environment:false, panel_capture:false, panel_server:false,
            app_pss_kb: '1000',
            app_pss_peak_kb: '2000',
            system_available_ram_kb: '3000',
            system_total_ram_kb: '4000'
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

        // Check specific expected values for mixed readings
        const expectedValues = ["1.0 MB", "2.0 MB", "3.1 MB", "4.1 MB"];
        const labels = ["PSS:", "PSS peak:", "Available RAM:", "Total RAM:"];

        for (let i = 0; i < labels.length; i++) {
            const matches = rows.filter(
                row => row.children[0].textContent === labels[i]
            );
            assert.strictEqual(matches.length, 1, `Expected one matching row for ${labels[i]}`);

            const actualValue = normalize(matches[0].children[1].textContent);
            assert.strictEqual(actualValue, expectedValues[i], `${labels[i]} value mismatch`);
        }

        console.log('PASS: mixed-distinct-readings');
    } catch (error) {
        failures++;
        console.log('FAIL: mixed-distinct-readings', '-', error.message);
    }

    // Test partial scenario showing field independence
    try {
        const elements = new Map();
        const get = id => {
            if (!elements.has(id)) elements.set(id, element());
            return elements.get(id);
        };
        const data = {
            app_cpu_current_normalized_percent:25,
                app_cpu_recent_average_normalized_percent:20,
                app_cpu_logical_count:8,
                app_cpu_normalized_stale:false,
                app_cpu_normalized_state:'available',
                panel_enabled:true, panel_device:true,
            panel_environment:false, panel_capture:false, panel_server:false,
            app_pss_kb: null,
            app_pss_peak_kb: '2000',
            system_available_ram_kb: '3000',
            system_total_ram_kb: '4000'
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

        // Verify all four independent partial readings.
        const partialExpected = [
            "Unavailable", "2.0 MB", "3.1 MB", "4.1 MB"
        ];
        for (let i = 0; i < memoryFields.length; i++) {
            const label = memoryFields[i].label;
            const matches = rows.filter(
                row => row.children[0].textContent === label
            );
            assert.strictEqual(
                matches.length, 1,
                `Expected one matching row for ${label}`
            );
            assert.strictEqual(
                normalize(matches[0].children[1].textContent),
                partialExpected[i],
                `${label} partial value mismatch`
            );
        }

        console.log('PASS: partial-field-independence');
    } catch (error) {
        failures++;
        console.log('FAIL: partial-field-independence', '-', error.message);
    }

    console.log(JSON.stringify({cases: cases.length + 2, failures}));
    process.exitCode = failures ? 1 : 0;
})().catch(error => {
    console.error(error);
    process.exitCode = 2;
});
