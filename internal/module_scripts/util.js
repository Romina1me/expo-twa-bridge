const { spawnSync } = require('child_process');

// On Windows, executables like `tsc` and `jest` are `.cmd` batch files and cannot be
// spawned directly — they require shell: true to resolve. On Unix, shell: true is
// unnecessary.
function quoteArg(arg) {
  return /[\s"]/.test(arg) ? `"${arg.replace(/"/g, '\\"')}"` : arg;
}

function spawnSyncWithAutoShell(command, args, options) {
  // Passing an `args` array together with `shell: true` triggers DEP0190. Build
  // the command line ourselves on Windows so the arguments are explicit.
  const result =
    process.platform === 'win32'
      ? spawnSync([command, ...args].map(quoteArg).join(' '), {
          ...options,
          shell: true,
        })
      : spawnSync(command, args, options);

  if (result.error) {
    console.error(
      `Couldn't start \`${command}\` (${result.error.message}). The module's dependencies are probably not installed. ` +
        `Run your package manager's install command in the module directory, then run this script again.`
    );
  }
  return result;
}

module.exports = { spawnSyncWithAutoShell };
