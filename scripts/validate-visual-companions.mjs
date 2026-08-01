#!/usr/bin/env node

import { lstat, readFile, realpath } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

import { validateManifest } from './visual-companions/contract.mjs';

const MANIFEST_PATH = 'docs/visual-companions/manifest.json';
const ALLOWED_DOCUMENT_ROOTS = ['docs/superpowers/specs/', 'docs/visual-companions/'];
const FORBIDDEN_SURFACES = [
  /<script\b[^>]*\bsrc\s*=/i,
  /<link\b[^>]*\brel\s*=\s*["']?stylesheet\b/i,
  /<(?:img|iframe|audio|video|source)\b[^>]*\bsrc\s*=\s*["']?\s*(?:https?:)?\/\//i,
  /<form\b/i,
  /\bfetch\s*\(/,
  /\bXMLHttpRequest\b/,
  /\bWebSocket\s*\(/,
  /\bnavigator\.sendBeacon\s*\(/,
  /url\(\s*["']?\s*(?:https?:)?\/\//i,
];

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function inside(root, candidate) {
  return candidate === root || candidate.startsWith(`${root}${path.sep}`);
}

function assertAllowedDocumentPath(value, field, errors) {
  if (!ALLOWED_DOCUMENT_ROOTS.some((root) => value.startsWith(root))) {
    errors.push(`${field} must be under one of ${ALLOWED_DOCUMENT_ROOTS.join(', ')}`);
  }
}

async function readContainedFile(root, relativePath, field, errors) {
  const absolutePath = path.resolve(root, relativePath);
  if (!inside(root, absolutePath)) {
    errors.push(`${field} escapes the repository root`);
    return null;
  }

  try {
    const stats = await lstat(absolutePath);
    if (stats.isSymbolicLink() || !stats.isFile()) {
      errors.push(`${field} must be a regular non-symlink file`);
      return null;
    }
    const canonicalPath = await realpath(absolutePath);
    if (!inside(root, canonicalPath)) {
      errors.push(`${field} resolves outside the repository root`);
      return null;
    }
    return await readFile(canonicalPath, 'utf8');
  } catch (error) {
    if (error?.code === 'ENOENT') {
      errors.push(`${field} does not exist: ${relativePath}`);
      return null;
    }
    throw error;
  }
}

function sectionContent(html, id) {
  const pattern = new RegExp(
    `<section\\b[^>]*\\bid=["']${escapeRegExp(id)}["'][^>]*>([\\s\\S]*?)<\\/section>`,
    'i',
  );
  return pattern.exec(html)?.[1] ?? null;
}

function validateHtml(document, locale, html, errors) {
  const field = `${document.id}.locales.${locale}`;
  const sourceName = path.posix.basename(document.source);
  const firstStyle = html.search(/<style\b/i);
  const themeBootstrap = html.indexOf('localStorage.getItem("starlight-theme")');

  if (!/^\s*<!doctype html>/i.test(html)) {
    errors.push(`${field} must start with <!doctype html>`);
  }
  if (!new RegExp(`<html\\b[^>]*\\blang=["']${locale}["']`, 'i').test(html)) {
    errors.push(`${field} must declare <html lang="${locale}">`);
  }
  if (
    !new RegExp(
      `class=["'][^"']*\\bprovenance\\b[^"']*["']`,
      'i',
    ).test(html)
  ) {
    errors.push(`${field} must contain a provenance block`);
  }
  if (
    !new RegExp(
      `data-status=["']${escapeRegExp(document.status)}["']`,
      'i',
    ).test(html)
  ) {
    errors.push(`${field} must declare data-status="${document.status}"`);
  }
  if (
    !new RegExp(
      `data-source=["'][^"']*${escapeRegExp(sourceName)}["']`,
      'i',
    ).test(html)
  ) {
    errors.push(`${field} must declare data-source for ${sourceName}`);
  }
  if (!/data-baseline=["'][0-9a-f]{7,40}["']/i.test(html)) {
    errors.push(`${field} must declare data-baseline with a Git commit`);
  }
  if (
    !/<meta\b[^>]*name=["']color-scheme["'][^>]*content=["']light dark["'][^>]*>/i.test(
      html,
    )
  ) {
    errors.push(`${field} must declare color-scheme="light dark"`);
  }
  if (themeBootstrap < 0 || firstStyle < 0 || themeBootstrap > firstStyle) {
    errors.push(
      `${field} must resolve starlight-theme before the first style block`,
    );
  }
  if (!/:root\[data-theme=["']light["']\]/i.test(html)) {
    errors.push(`${field} must define light-theme tokens`);
  }
  if (
    !/<button\b[^>]*class=["'][^"']*\btheme-toggle\b[^"']*["'][^>]*aria-label=["'][^"']+["']/i.test(
      html,
    )
  ) {
    errors.push(`${field} must contain an accessible theme toggle`);
  }
  if (!html.includes('localStorage.setItem("starlight-theme"')) {
    errors.push(`${field} must persist the selected starlight-theme`);
  }
  if (
    !new RegExp(
      `href=["'][^"']*${escapeRegExp(sourceName)}(?:#[^"']*)?["']`,
      'i',
    ).test(html)
  ) {
    errors.push(`${field} must contain a Markdown backlink to ${sourceName}`);
  }

  for (const view of document.presentation.views) {
    if (!sectionContent(html, view)) {
      errors.push(`${field} must contain #${view}`);
    }
  }
  if (document.presentation.mode === 'hybrid') {
    const simulation = sectionContent(html, 'simulation');
    const history = sectionContent(html, 'history');
    if (simulation && !/href=["']#history["']/i.test(simulation)) {
      errors.push(`${field} simulation must link to #history`);
    }
    if (history && !/href=["']#simulation["']/i.test(history)) {
      errors.push(`${field} history must link to #simulation`);
    }
  }

  if (FORBIDDEN_SURFACES.some((pattern) => pattern.test(html))) {
    errors.push(`${field} contains a forbidden surface`);
  }
}

export async function validateRepository(repositoryRoot = process.cwd()) {
  const root = await realpath(repositoryRoot);
  const errors = [];
  const manifestText = await readContainedFile(
    root,
    MANIFEST_PATH,
    'manifest',
    errors,
  );
  if (manifestText === null) {
    throw new Error(errors.join('\n'));
  }

  let manifest;
  try {
    manifest = validateManifest(JSON.parse(manifestText));
  } catch (error) {
    throw new Error(`manifest contract failed: ${error.message}`);
  }

  let localeFileCount = 0;
  for (const [index, document] of manifest.documents.entries()) {
    const sourceField = `documents[${index}].source`;
    assertAllowedDocumentPath(document.source, sourceField, errors);
    const markdown = await readContainedFile(
      root,
      document.source,
      sourceField,
      errors,
    );

    for (const locale of ['en', 'ko']) {
      const localeEntry = document.locales[locale];
      const htmlField = `documents[${index}].locales.${locale}.html`;
      assertAllowedDocumentPath(localeEntry.html, htmlField, errors);
      const html = await readContainedFile(
        root,
        localeEntry.html,
        htmlField,
        errors,
      );
      if (markdown !== null) {
        const htmlName = path.posix.basename(localeEntry.html);
        if (!markdown.includes(htmlName)) {
          errors.push(`${sourceField} must link to ${htmlName}`);
        }
      }
      if (html !== null) {
        localeFileCount += 1;
        validateHtml(document, locale, html, errors);
      }
    }
  }

  if (errors.length > 0) {
    throw new Error(errors.join('\n'));
  }
  return {
    documentCount: manifest.documents.length,
    localeFileCount,
  };
}

async function main() {
  try {
    const result = await validateRepository();
    console.log(
      `Visual companion validation passed: ${result.documentCount} documents / ${result.localeFileCount} locale files`,
    );
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
) {
  await main();
}
