// Pure, DOM-free bridge logic for the NexG editor WebView.
//
// Imported by the browser bootstrap (editor-bootstrap.mjs) and by the node test
// suite (test/editor-core.test.mjs). Nothing in this file may touch window,
// document, rAF or the EditorView DOM, so its behaviour is verifiable without a
// browser. The message shapes here mirror the Kotlin side exactly
// (ui.editor.webview.EditorCodec); keep the two in lockstep.

import { StreamLanguage } from "@codemirror/language";
import { java } from "@codemirror/lang-java";
import { json } from "@codemirror/lang-json";
import { markdown } from "@codemirror/lang-markdown";
import { xml } from "@codemirror/lang-xml";
import { kotlin } from "@codemirror/legacy-modes/mode/clike";
import { shell } from "@codemirror/legacy-modes/mode/shell";
import { undo, redo } from "@codemirror/commands";
import { openSearchPanel } from "@codemirror/search";

/** The language id that means "no parser" (plain text). */
export const PLAIN_LANGUAGE = "plain";

/**
 * PLAN.MD languages: Kotlin, Java, XML, Gradle / Gradle Kotlin DSL, JSON,
 * Markdown, Shell. Gradle uses the Kotlin parser, matching the Android side's
 * existing decision to share the Kotlin vocabulary with Gradle files.
 * Anything not listed here is intentionally NOT a language.
 */
export const LANGUAGE_IDS = Object.freeze([
  PLAIN_LANGUAGE,
  "kotlin",
  "java",
  "xml",
  "gradle",
  "json",
  "markdown",
  "shell",
]);

const FACTORIES = {
  kotlin: () => StreamLanguage.define(kotlin),
  java: () => java(),
  xml: () => xml(),
  gradle: () => StreamLanguage.define(kotlin),
  json: () => json(),
  markdown: () => markdown(),
  shell: () => StreamLanguage.define(shell),
  [PLAIN_LANGUAGE]: () => null,
};

/** LanguageSupport for a database-id, or null for plain text (incl. unknown ids). */
export function languageFor(id) {
  const factory = Object.hasOwn(FACTORIES, id) ? FACTORIES[id] : null;
  return factory ? factory() : null;
}

export function isKnownLanguage(id) {
  return LANGUAGE_IDS.includes(id);
}

/**
 * The closed whitelist of commands the Kotlin->JS bridge may ask for. Each maps
 * to a real CodeMirror command; anything else is refused. This is the
 * "narrow, whitelisted" surface of the bridge contract.
 */
export const OK_COMMANDS = Object.freeze(["undo", "redo", "find"]);

const COMMAND_FNS = Object.freeze({
  undo,
  redo,
  find: openSearchPanel,
});

/** The command target for [name], or null when the name is not whitelisted. */
export function knownCommand(name) {
  return COMMAND_FNS[name] || null;
}

/**
 * Runs a whitelisted command against a view. Accepts any object exposing the
 * Command shape, which is what makes it testable with a fake view here and
 * work with the real EditorView in the browser.
 */
export function execCommand(view, name) {
  const fn = knownCommand(name);
  if (!fn || !view || typeof fn !== "function") return false;
  try {
    return fn(view) === true;
  } catch {
    return false;
  }
}

// ------------------------------------------------------------------- messages

/** JS->Kotlin message types Android's bridge accepts (mirror of EditorCodec). */
export const MESSAGE_TYPES = Object.freeze([
  "editorReady",
  "documentChanged",
  "selectionChanged",
  "saveRequested",
  "editorError",
]);

/** Builds the JSON string posted to Kotlin via window.android.postMessage. */
export function encodeMessage(type, payload) {
  if (!MESSAGE_TYPES.includes(type)) {
    throw new Error(`unknown message type: ${type}`);
  }
  return JSON.stringify({ type, payload: payload == null ? {} : payload });
}

/** Strict JSON parse of a single bridge argument; malformed input throws. */
export function parseJson(raw, label = "message") {
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new Error(`${label}: not valid JSON`);
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(`${label}: not an object`);
  }
  return parsed;
}

/** Coerces a bridge number safely; anything non-numeric falls back. */
export function clampInt(value, fallback, lo, hi) {
  let n;
  if (typeof value === "number" && Number.isFinite(value)) n = value;
  else if (typeof value === "string" && /^-?\d+$/.test(value)) n = Number(value);
  else return fallback;
  return Math.max(lo, Math.min(hi, Math.floor(n)));
}

/**
 * Normalizes an offset pair into a sorted, in-bounds selection for
 * EditorState. Kotlin always sends sorted, in-bounds pairs, but the bridge is
 * a trust boundary: this does not trust that.
 */
export function normalizedSelection(payload, docLength) {
  const length = clampInt(docLength, 0, 0, Number.MAX_SAFE_INTEGER);
  const start = clampInt(payload && payload.start, 0, 0, length);
  const end = clampInt(payload && payload.end, start, 0, length);
  return { start: Math.min(start, end), end: Math.max(start, end) };
}

/** Hard cap before a document is pushed to Kotlin (belt-and-braces on top of
 * the Android-side 2 MB editable limit). */
export const MAX_DOCUMENT_CHARS = 8 * 1024 * 1024;

// ------------------------------------------------------------------ tracker

/**
 * Coalesces the per-keystroke EditorView update flood into at most one
 * document + selection notification per tick. The tick is injected
 * (requestAnimationFrame in the browser, explicit flushes in tests) so the
 * scheduling is testable without a DOM, and a "schedule" that is already
 * pending is a no-op — many keystrokes in one frame cost one message.
 */
export function makeChangeTracker({ onDocument, onSelection, tick = (fn) => fn() }) {
  let docChanged = false;
  let selectionChanged = false;
  let lastDoc = null;
  let lastSelectionStart = -1;
  let lastSelectionEnd = -1;
  let scheduled = false;

  function flushNow(getState) {
    scheduled = false;
    const state = getState();
    if (docChanged) {
      docChanged = false;
      const text = state.doc.toString();
      if (text.length > MAX_DOCUMENT_CHARS) {
        // Refuse to push a document far beyond the editable limit. Kotlin will
        // already have shown it read-only; shipping the text anyway would just
        // burn memory on the JVM side.
        onErrorSafe("document exceeds bridge limit");
        return;
      }
      if (text !== lastDoc) {
        lastDoc = text;
        onDocument(text);
      }
    }
    if (selectionChanged) {
      selectionChanged = false;
      const main = state.selection.main;
      if (main.from !== lastSelectionStart || main.to !== lastSelectionEnd) {
        lastSelectionStart = main.from;
        lastSelectionEnd = main.to;
        onSelection(main.from, main.to);
      }
    }
  }

  return {
    schedule(getState, docChangedNow, selectionChangedNow) {
      docChanged = docChanged || docChangedNow;
      selectionChanged = selectionChanged || selectionChangedNow;
      if (scheduled) return;
      scheduled = true;
      tick(() => flushNow(getState));
    },
    flush(getState) {
      if (scheduled) flushNow(getState);
    },
    /** Drops the sent-text watermark, so the next push is always forwarded. */
    reset() {
      lastDoc = null;
      lastSelectionStart = -1;
      lastSelectionEnd = -1;
    },
  };
}

// Error reports need an escape hatch that isn't part of the construction
// options; the bootstrap wires it.
let onErrorSafe = () => {};
export function setChangeTrackerErrorHandler(fn) {
  onErrorSafe = typeof fn === "function" ? fn : () => {};
}