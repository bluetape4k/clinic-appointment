import { spawnSync } from 'node:child_process';

const COMMANDS = Object.freeze({
  xcodebuild: Object.freeze({ args: ['-version'], target: 'ios' }),
  xcrun: Object.freeze({ args: ['simctl', 'list', 'devices', 'available'], target: 'ios' }),
  adb: Object.freeze({ args: ['version'], target: 'android' }),
  sdkmanager: Object.freeze({ args: ['--version'], target: 'android' }),
  java: Object.freeze({ args: ['-version'], target: 'shared' }),
  git: Object.freeze({ args: ['--version'], target: 'shared' }),
});

function defaultCommandRunner(command, args) {
  const result = spawnSync(command, args, {
    encoding: 'utf8',
    timeout: 10_000,
    windowsHide: true,
  });

  return {
    status: result.status,
    stdout: result.stdout ?? '',
    stderr: result.stderr ?? '',
    errorCode: result.error?.code,
  };
}

function firstSafeLine(value) {
  const line = String(value ?? '')
    .split(/\r?\n/u)
    .map((part) => part.trim())
    .find(Boolean);
  return line ? line.slice(0, 200) : null;
}

function inspectCommand(command, definition, commandRunner) {
  const result = commandRunner(command, definition.args);
  const available = result.status === 0;
  const reason = available
    ? undefined
    : result.errorCode === 'ENOENT'
      ? 'command-not-found'
      : 'command-failed';

  return {
    command,
    target: definition.target,
    available,
    ...(firstSafeLine(result.stdout) ? { version: firstSafeLine(result.stdout) } : {}),
    ...(reason ? { reason } : {}),
  };
}

export function collectNativeEnvironment({
  commandRunner = defaultCommandRunner,
  now = new Date(),
} = {}) {
  const commands = Object.fromEntries(
    Object.entries(COMMANDS).map(([command, definition]) => [
      command,
      inspectCommand(command, definition, commandRunner),
    ]),
  );
  const iosSimulatorListed = commands.xcrun.available;
  const androidToolchainAvailable = commands.adb.available && commands.sdkmanager.available;

  return {
    generatedBy: 'validate-native-webview-environment',
    generatedAt: now.toISOString(),
    platform: process.platform,
    runtime: process.version,
    commands,
    targets: {
      ios: commands.xcodebuild.available && iosSimulatorListed,
      android: androidToolchainAvailable,
    },
  };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  console.log(JSON.stringify(collectNativeEnvironment(), null, 2));
}
