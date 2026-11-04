// Copyright (C) 2026 David González López-Tercero
// SPDX-License-Identifier: GPL-3.0-or-later
// See LICENSE in the repository root for licensing details.
// Runs the generated viewer script against a minimal DOM stub: node viewer-handlers-test.js script.js
'use strict';
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const elements = {};
function element(id) {
    const hiddenByDefault = id === 'settings' || id === 'about';
    const target = {id, hidden: hiddenByDefault, dataset: {}, style: {}, value: '', elements: [], textContent: ''};
    return new Proxy(target, {get: (t, k) => k in t ? t[k] : (k === 'querySelectorAll' ? () => [] : () => {}),
        set: (t, k, v) => { t[k] = v; return true; }});
}
const document = {getElementById: id => elements[id] || (elements[id] = element(id)), createElement: () => element('x')};
const window = {addEventListener() {}};
const context = {document, window, fetch: () => Promise.reject(new Error('offline')), setInterval() {},
    requestAnimationFrame() {}, getComputedStyle: () => ({}), confirm: () => false};
vm.createContext(context);
vm.runInContext(fs.readFileSync(process.argv[2], 'utf8'), context);
const settings = document.getElementById('settings'), about = document.getElementById('about');
assert.strictEqual(about.hidden, true, 'About starts hidden');
document.getElementById('about-button').onclick();
assert.strictEqual(about.hidden, false, 'About opens without a token');
assert.strictEqual(settings.hidden, true, 'About does not open Settings');
document.getElementById('about-close').onclick();
assert.strictEqual(about.hidden, true, 'About Close hides panel');
document.getElementById('settings-button').onclick();
assert.strictEqual(settings.hidden, false, 'Settings opens');
document.getElementById('settings-button').onclick();
assert.strictEqual(settings.hidden, true, 'Settings toggles closed');
console.log('PASS: viewer Settings/About handlers');
