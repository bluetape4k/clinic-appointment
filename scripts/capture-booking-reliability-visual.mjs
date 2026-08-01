#!/usr/bin/env node

import { access, copyFile, mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import { constants } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';

const ROOT = process.cwd();
const VISUAL_DIR = path.join(ROOT, 'docs/visual-companions');
const BASE_NAME = 'booking-reliability-workflow';
const VIEWPORT = { width: 1440, height: 1000 };
const CHROME_CANDIDATES = [
  process.env.CHROME_BIN,
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
  '/usr/bin/google-chrome',
  '/usr/bin/google-chrome-stable',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
].filter(Boolean);

async function findChrome() {
  for (const candidate of CHROME_CANDIDATES) {
    try {
      await access(candidate, constants.X_OK);
      return candidate;
    } catch {
      // Try the next known executable.
    }
  }
  throw new Error('Chrome/Chromium was not found. Set CHROME_BIN to an executable browser path.');
}

function sha256(buffer) {
  return createHash('sha256').update(buffer).digest('hex');
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function stopBrowser(child) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  child.kill('SIGTERM');
  await Promise.race([
    new Promise((resolve) => child.once('exit', resolve)),
    delay(2_000),
  ]);
  if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
}

async function capture(chrome, input, output, theme, profileDirectory) {
  const url = new URL(pathToFileURL(input));
  url.searchParams.set('theme', theme);
  const child = spawn(chrome, [
    '--headless=new',
    '--disable-gpu',
    '--disable-extensions',
    '--disable-background-networking',
    '--no-first-run',
    '--no-default-browser-check',
    '--hide-scrollbars',
    '--run-all-compositor-stages-before-draw',
    '--force-device-scale-factor=1',
    `--window-size=${VIEWPORT.width},${VIEWPORT.height}`,
    `--user-data-dir=${profileDirectory}`,
    `--screenshot=${output}`,
    '--virtual-time-budget=1000',
    url.href,
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  let stderr = '';
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk) => { stderr += chunk; });

  let captured = false;
  for (let attempt = 0; attempt < 200; attempt += 1) {
    try {
      const outputStat = await stat(output);
      if (outputStat.size > 0) {
        captured = true;
        break;
      }
    } catch {
      // Chrome has not completed the screenshot yet.
    }
    if (child.exitCode !== null || child.signalCode !== null) break;
    await delay(50);
  }
  await stopBrowser(child);
  if (!captured) throw new Error(`Chrome capture did not produce ${output}: ${stderr}`);
}

async function captureDeterministically(chrome, locale, theme, tempRoot) {
  const input = path.join(VISUAL_DIR, `${BASE_NAME}-${locale}-${theme}.html`);
  const output = path.join(VISUAL_DIR, `${BASE_NAME}-${locale}-${theme}.png`);
  const first = path.join(tempRoot, `${locale}.${theme}.first.png`);
  const second = path.join(tempRoot, `${locale}.${theme}.second.png`);
  await capture(chrome, input, first, theme, path.join(tempRoot, `${locale}.${theme}.profile-1`));
  await capture(chrome, input, second, theme, path.join(tempRoot, `${locale}.${theme}.profile-2`));
  const [firstBytes, secondBytes] = await Promise.all([readFile(first), readFile(second)]);
  const firstHash = sha256(firstBytes);
  const secondHash = sha256(secondBytes);
  if (firstHash !== secondHash) throw new Error(`${locale}/${theme} capture is not deterministic: ${firstHash} != ${secondHash}`);
  await copyFile(first, output);
  console.log(`${path.relative(ROOT, output)} ${firstHash}`);
}

async function main() {
  const chrome = await findChrome();
  const tempRoot = await mkdtemp(path.join(tmpdir(), 'booking-reliability-visual-'));
  try {
    for (const locale of ['en', 'ko']) {
      for (const theme of ['light', 'dark']) await captureDeterministically(chrome, locale, theme, tempRoot);
    }
  } finally {
    await rm(tempRoot, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
