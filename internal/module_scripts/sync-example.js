#!/usr/bin/env node
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const exampleModules = path.join(root, 'example', 'node_modules');
const target = path.join(exampleModules, 'expo-twa-bridge');

if (!fs.existsSync(target)) {
  console.log(
    'example/node_modules/expo-twa-bridge is not installed; run `npm install` in example/ first.'
  );
  process.exit(0);
}

// The example installs this package with `file:..`, which is a snapshot copy.
// Copy the freshly built JS and native sources so the example never runs the
// package through a stale build after `npm run build`.
const entries = [
  'build',
  'src',
  'android/src',
  'android/build.gradle',
  'plugin/build',
  'expo-module.config.json',
  'app.plugin.js',
  'package.json',
];

for (const entry of entries) {
  const from = path.join(root, entry);
  if (!fs.existsSync(from)) {
    continue;
  }
  const to = path.join(target, entry);
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.cpSync(from, to, { recursive: true, force: true });
}

console.log(`Synced the package build into ${target}.`);
