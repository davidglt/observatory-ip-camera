// Copyright (C) 2026 David González López-Tercero
// SPDX-License-Identifier: GPL-3.0-or-later
// See LICENSE in the repository root for licensing details.
// Runs the generated viewer script against a minimal DOM stub: node viewer-handlers-test.js script.js
'use strict';
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const elements = {};
const fetches = [];
function element(id) {
    const hiddenByDefault = id === 'settings' || id === 'about' || id === 'license-dialog';
    const target = {id, hidden: hiddenByDefault, dataset: {}, style: {}, value: '', elements: [], children: [], textContent: '',
        append(...items) { this.children.push(...items); }, replaceChildren(...items) { this.children = items; },
        focus() {}, click() {}};
    return new Proxy(target, {get: (t, k) => k in t ? t[k] : (k === 'querySelectorAll' ? () => [] : () => {}),
        set: (t, k, v) => { t[k] = v; return true; }});
}
const document = {getElementById: id => elements[id] || (elements[id] = element(id)), createElement: () => element('x')};
const window = {addEventListener() {}};
const fetch = (path, options = {}) => {
    fetches.push({path, options});
    if (path === '/license.txt')
        return Promise.resolve({ok: true, text: () => Promise.resolve('GNU GENERAL PUBLIC LICENSE\nGPL-3.0-or-later')});
    if (path === '/api/telemetry') return Promise.resolve({ok: true, json: () => Promise.resolve({
        panel_enabled: true, panel_text_color: '#ff0000', panel_background_color: '#000000', telemetry_position: 'right',
        panel_text_size: 16, panel_environment: true, environment_temperature_c: '20.0', environment_humidity_pct: '40.0',
        environment_pressure_hpa: '1013.0', environment_state: 'fresh', panel_device: true, battery_percent: '75',
        battery_temperature_c: '25.0', charging: 'not charging', thermal_status: 'none', app_cpu_current_percent: 'Collecting',
        app_cpu_recent_average_percent: 'Collecting', app_pss_kb: '1000', app_pss_peak_kb: '1100', app_uptime: '1 h',
        system_available_ram_kb: '2000', system_total_ram_kb: '4000', system_low_memory: 'Normal',
        panel_capture: true, capture_state: 'stopped', capture_frames: 3, latest_image_age_seconds: '8.0',
        actual_fps: '1.00', requested_fps: '1.00', processing_last_ms: '2.0',
        processing_average_ms: '3.0', processing_maximum_ms: '4.0', panel_server: true,
        server_service_state: 'running', server_requested_listeners: 'http://0.0.0.0:8080',
        server_effective_listeners: 'http://127.0.0.1:8080', server_listener_error: 'none',
        server_active_connections: '1', server_active_streams: '1'
    })});
    return Promise.reject(new Error('offline'));
};
const context = {document, window, fetch, setInterval() {},
    requestAnimationFrame() {}, getComputedStyle: () => ({}), confirm: () => false};
vm.createContext(context);
vm.runInContext(fs.readFileSync(process.argv[2], 'utf8'), context);
const settings = document.getElementById('settings'), about = document.getElementById('about');
(async () => {
    const dialog = document.getElementById('license-dialog');
    assert.strictEqual(about.hidden, true, 'About starts hidden');
    document.getElementById('about-button').onclick();
    assert.strictEqual(about.hidden, false, 'About opens without a token');
    assert.strictEqual(settings.hidden, true, 'About does not open Settings');
    await new Promise(resolve => setImmediate(resolve));
    const panel = document.getElementById('telemetry');
    const titles = panel.children.map(group => group.children[0].textContent);
    assert.deepStrictEqual(titles, ['Environment', 'Device', 'Capture', 'Server'], 'Server is a separate group');
    const device = panel.children[1];
    assert.deepStrictEqual(device.children.slice(-2).map(group => group.children[0].textContent),
        ['Application', 'System'], 'Application and System are Device subsections');
    const capture = panel.children[2];
    assert.strictEqual(capture.children[1].children[1].textContent, 'Not capturing', 'stopped capture is explicit');
    assert.match(capture.children[4].children[0].textContent, /last-session statistics/,
        'retained processing statistics identify their last session');
    await document.getElementById('license-button').onclick();
    assert.strictEqual(dialog.hidden, false, 'License opens from About');
    assert.strictEqual(about.hidden, true, 'License replaces About while open');
    assert.match(document.getElementById('license-content').textContent, /GNU GENERAL PUBLIC LICENSE/);
    const licenseRequest = fetches.find(request => request.path === '/license.txt');
    assert.ok(licenseRequest, 'License is loaded from the local offline endpoint');
    assert.strictEqual(licenseRequest.options.credentials, 'omit', 'License fetch has no credentials');
    assert.strictEqual(licenseRequest.options.headers, undefined, 'License fetch does not use the admin token');
    document.getElementById('license-back').onclick();
    assert.strictEqual(dialog.hidden, true, 'Back closes the license');
    assert.strictEqual(about.hidden, false, 'Back returns to About');
    await document.getElementById('license-button').onclick();
    assert.strictEqual(fetches.filter(request => request.path === '/license.txt').length, 1, 'loaded license is reused');
    document.getElementById('license-close').onclick();
    assert.strictEqual(dialog.hidden, true, 'License Close hides the dialog');
    assert.strictEqual(about.hidden, true, 'License Close returns to the viewer');
    document.getElementById('about-button').onclick();
    document.getElementById('about-close').onclick();
    assert.strictEqual(about.hidden, true, 'About Close hides panel');
    document.getElementById('settings-button').onclick();
    assert.strictEqual(settings.hidden, false, 'Settings opens');
    document.getElementById('settings-button').onclick();
    assert.strictEqual(settings.hidden, true, 'Settings toggles closed');
    console.log('PASS: viewer Settings/About/License handlers');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
