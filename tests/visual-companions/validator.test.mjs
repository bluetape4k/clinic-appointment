import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { validateRepository } from '../../scripts/validate-visual-companions.mjs';

const SOURCE = 'docs/superpowers/specs/design.md';
const EN_HTML = 'docs/superpowers/specs/design.en.html';
const KO_HTML = 'docs/superpowers/specs/design.html';

function manifest() {
  return {
    schemaVersion: 1,
    repository: 'bluetape4k/clinic-appointment',
    documents: [
      {
        id: 'design',
        source: SOURCE,
        status: 'approved',
        public: true,
        presentation: {
          mode: 'hybrid',
          defaultView: 'simulation',
          views: ['simulation', 'history'],
        },
        locales: {
          en: { title: 'Design', html: EN_HTML },
          ko: { title: '설계', html: KO_HTML },
        },
      },
    ],
  };
}

function html(locale) {
  return `<!doctype html>
<html lang="${locale}">
<head>
  <meta charset="utf-8">
  <meta name="color-scheme" content="light dark">
  <title>Design</title>
  <script>localStorage.getItem("starlight-theme"); document.documentElement.dataset.theme = "light";</script>
  <style>:root[data-theme="light"] { color: #111; }</style>
</head>
<body>
  <button class="theme-toggle" aria-label="Switch theme">Theme</button>
  <header>
    <dl class="provenance"
        data-status="approved"
        data-source="design.md"
        data-baseline="0123456789abcdef0123456789abcdef01234567">
      <dt>Status</dt><dd>approved</dd>
      <dt>Source</dt><dd><a href="./design.md">design.md</a></dd>
      <dt>Baseline</dt><dd><code>0123456789abcdef0123456789abcdef01234567</code></dd>
    </dl>
  </header>
  <main>
    <section id="simulation">
      <h2>Simulation</h2>
      <a href="#history">History</a>
    </section>
    <section id="history">
      <h2>History</h2>
      <a href="#simulation">Simulation</a>
      <a href="https://github.com/bluetape4k/clinic-appointment/issues/182">Issue</a>
    </section>
  </main>
  <script>localStorage.setItem("starlight-theme", "light");</script>
</body>
</html>`;
}

async function createFixture(t) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'visual-companion-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(path.join(root, 'docs/visual-companions'), { recursive: true });
  await mkdir(path.join(root, 'docs/superpowers/specs'), { recursive: true });
  await writeFile(
    path.join(root, 'docs/visual-companions/manifest.json'),
    `${JSON.stringify(manifest(), null, 2)}\n`,
  );
  await writeFile(
    path.join(root, SOURCE),
    '# Design\n\n[English HTML](./design.en.html) | [한국어 HTML](./design.html)\n',
  );
  await writeFile(path.join(root, EN_HTML), html('en'));
  await writeFile(path.join(root, KO_HTML), html('ko'));
  return root;
}

async function replace(root, relativePath, pattern, replacement) {
  const target = path.join(root, relativePath);
  const content = await readFile(target, 'utf8');
  await writeFile(target, content.replace(pattern, replacement));
}

test('accepts a valid bilingual hybrid companion pair', async (t) => {
  const root = await createFixture(t);
  assert.deepEqual(await validateRepository(root), {
    documentCount: 1,
    localeFileCount: 2,
  });
});

test('rejects a missing source or locale HTML file', async (t) => {
  const root = await createFixture(t);
  await rm(path.join(root, EN_HTML));
  await assert.rejects(
    () => validateRepository(root),
    /documents\[0\]\.locales\.en\.html.*does not exist/,
  );
});

test('requires both hybrid section anchors', async (t) => {
  const root = await createFixture(t);
  await replace(root, KO_HTML, 'id="history"', 'id="timeline"');
  await assert.rejects(
    () => validateRepository(root),
    /design\.locales\.ko.*#history/,
  );
});

test('requires bidirectional navigation between hybrid views', async (t) => {
  const root = await createFixture(t);
  await replace(root, EN_HTML, 'href="#simulation"', 'href="#overview"');
  await assert.rejects(
    () => validateRepository(root),
    /design\.locales\.en.*history.*#simulation/,
  );
});

test('requires Markdown links to both locale files', async (t) => {
  const root = await createFixture(t);
  await replace(root, SOURCE, '[한국어 HTML](./design.html)', '');
  await assert.rejects(
    () => validateRepository(root),
    /documents\[0\]\.source.*design\.html/,
  );
});

test('requires each HTML file to link back to its Markdown source', async (t) => {
  const root = await createFixture(t);
  await replace(root, KO_HTML, 'href="./design.md"', 'href="./other.md"');
  await assert.rejects(
    () => validateRepository(root),
    /design\.locales\.ko.*backlink.*design\.md/,
  );
});

test('requires locale-specific html lang values', async (t) => {
  const root = await createFixture(t);
  await replace(root, EN_HTML, '<html lang="en">', '<html lang="ko">');
  await assert.rejects(
    () => validateRepository(root),
    /design\.locales\.en.*lang="en"/,
  );
});

test('requires status, source, and baseline provenance metadata', async (t) => {
  const root = await createFixture(t);
  await replace(root, EN_HTML, 'data-baseline=', 'data-revision=');
  await assert.rejects(
    () => validateRepository(root),
    /design\.locales\.en.*data-baseline/,
  );
});

test('requires the shared light and dark theme contract', async (t) => {
  const root = await createFixture(t);
  await replace(
    root,
    EN_HTML,
    '<meta name="color-scheme" content="light dark">',
    '<meta name="color-scheme" content="dark">',
  );
  await assert.rejects(
    () => validateRepository(root),
    /design\.locales\.en.*color-scheme="light dark"/,
  );
});

test('rejects active external resources, forms, and network APIs', async (t) => {
  const forbidden = [
    ['</head>', '<script src="https://example.com/app.js"></script></head>'],
    ['</head>', '<link rel="stylesheet" href="https://example.com/app.css"></head>'],
    ['</main>', '<form action="/submit"></form></main>'],
    ['</main>', '<img src="https://example.com/patient.png"></main>'],
    ['</body>', '<script>fetch("/api")</script></body>'],
    ['</body>', '<script>new XMLHttpRequest()</script></body>'],
    ['</body>', '<script>new WebSocket("wss://example.com")</script></body>'],
  ];

  for (const [pattern, replacement] of forbidden) {
    await t.test(replacement, async (child) => {
      const root = await createFixture(child);
      await replace(root, EN_HTML, pattern, replacement);
      await assert.rejects(() => validateRepository(root), /forbidden surface/);
    });
  }
});

test('does not inspect or publish HTML omitted from the manifest', async (t) => {
  const root = await createFixture(t);
  await writeFile(
    path.join(root, 'docs/superpowers/specs/internal-plan.html'),
    '<form action="https://example.com"></form>',
  );
  assert.deepEqual(await validateRepository(root), {
    documentCount: 1,
    localeFileCount: 2,
  });
});
