// Node tests for the DOM-free editor bridge logic.
//
// These pin the pieces that keep Android and CodeMirror in lockstep: language
// mapping, message encode/decode, offset clamping, the closed command
// whitelist and the coalescing tracker. They deliberately do NOT claim mobile
// touch behaviour — tap-to-place-caret, long-press selection, drag handles,
// copy/paste and scrolling all require the device verification checklist in
// the Phase report.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import {
  PLAIN_LANGUAGE,
  LANGUAGE_IDS,
  languageFor,
  isKnownLanguage,
  OK_COMMANDS,
  knownCommand,
  execCommand,
  MESSAGE_TYPES,
  encodeMessage,
  parseJson,
  clampInt,
  normalizedSelection,
  makeChangeTracker,
  setChangeTrackerErrorHandler,
} from "../src/editor-core.mjs";

// ------------------------------------------------------------- language init

// legacy StreamLanguage factories return the Language itself (name at the top
// level); modern language packages return LanguageSupport (name on .language).
function languageNameOf(support) {
  return (support.language ?? support).name;
}

test("every PLAN.MD language resolves to a parser", () => {
  const plain = languageFor("plain");
  assert.equal(plain, null);

  for (const id of ["kotlin", "java", "xml", "gradle", "json", "markdown", "shell"]) {
    const support = languageFor(id);
    assert.ok(support, `${id} should resolve`);
    assert.ok(
      typeof languageNameOf(support) === "string" && languageNameOf(support).length > 0,
      `${id} should carry a language name`,
    );
  }
});

test("gradle reuses the Kotlin parser", () => {
  const gradle = languageFor("gradle");
  assert.match(languageNameOf(gradle), /kotlin/i);
  assert.equal(languageNameOf(languageFor("kotlin")), languageNameOf(gradle));
});

test("unknown and hostile language ids fall back to plain text", () => {
  assert.equal(languageFor("javascript"), null);
  assert.equal(languageFor("ko tlin"), null);
  assert.equal(languageFor(""), null);
  assert.equal(languageFor(undefined), null);
  assert.equal(isKnownLanguage("java"), true);
  assert.equal(isKnownLanguage("brainfuck"), false);
});

// -------------------------------------------------------------- commands

test("command whitelist is closed and maps to functions", () => {
  assert.deepEqual(OK_COMMANDS, ["undo", "redo", "find"]);
  for (const name of OK_COMMANDS) assert.equal(typeof knownCommand(name), "function");
  assert.equal(knownCommand("selectAll"), null);
  assert.equal(knownCommand("eval"), null);
  assert.equal(knownCommand("exec('rm -rf /')"), null);
});

test("execCommand runs only whitelisted commands and reports success", () => {
  // The guard: a non-whitelisted name is refused before any command runs.
  assert.equal(execCommand(null, "undo"), false);
  assert.equal(execCommand({}, "undo"), false);
  assert.equal(execCommand(undefined, "undo"), false);
  assert.equal(execCommand({}, "selectAll"), false, "non-whitelisted -> refused");
  assert.equal(execCommand({}, "eval"), false);
  // A whitelisted command invoked against an object that cannot honour it is
  // reported as not handled (never thrown across the bridge).
  assert.equal(execCommand({}, "undo"), false);
  assert.equal(execCommand({}, "find"), false);
});

test("the undo/redo/find targets exist in the real package surface", async () => {
  assert.equal(knownCommand("undo"), (await import("@codemirror/commands")).undo);
  assert.equal(knownCommand("redo"), (await import("@codemirror/commands")).redo);
});

// -------------------------------------------------------------- messages

test("encodeMessage builds the Android wire format", () => {
  assert.equal(encodeMessage("editorReady", {}), '{"type":"editorReady","payload":{}}');
  const doc = encodeMessage("documentChanged", { text: "hi" });
  assert.deepEqual(JSON.parse(doc), { type: "documentChanged", payload: { text: "hi" } });
  assert.throws(() => encodeMessage("nope", {}), /unknown message type/);
});

test("the message whitelist is exactly the agreed five names", () => {
  assert.deepEqual(MESSAGE_TYPES, [
    "editorReady",
    "documentChanged",
    "selectionChanged",
    "saveRequested",
    "editorError",
  ]);
  // The bare "error" spelling from an earlier draft is not an accepted alias.
  assert.throws(() => encodeMessage("error", { message: "x" }), /unknown message type/);
});

test("parseJson rejects malformed bridge input", () => {
  assert.throws(() => parseJson("not json"), /not valid JSON/);
  assert.throws(() => parseJson("[]"), /not an object/);
  assert.throws(() => parseJson("null"), /not an object/);
  assert.throws(() => parseJson('"str"'), /not an object/);
  assert.deepEqual(parseJson('{"type":"documentChanged","payload":{"text":"x"}}'), {
    type: "documentChanged",
    payload: { text: "x" },
  });
});

// -------------------------------------------------------------- offsets

test("clampInt coerces and bounds bridge numbers", () => {
  assert.equal(clampInt(5, 0, 0, 10), 5);
  assert.equal(clampInt(-3, 0, 0, 10), 0);
  assert.equal(clampInt(99, 0, 0, 10), 10);
  assert.equal(clampInt(1.9, 0, 0, 10), 1);
  assert.equal(clampInt("42", 0, 0, 100), 42);
  assert.equal(clampInt("1e3", 0, 0, 10), 0, "exponential string not numeric");
  assert.equal(clampInt(undefined, 7, 0, 10), 7);
  assert.equal(clampInt({}, 7, 0, 10), 7);
});

test("normalizedSelection clamps into bounds and sorts", () => {
  assert.deepEqual(normalizedSelection({ start: 5, end: 2 }, 10), { start: 2, end: 5 });
  assert.deepEqual(normalizedSelection({ start: -5, end: 999 }, 10), { start: 0, end: 10 });
  assert.deepEqual(normalizedSelection(null, 10), { start: 0, end: 0 });
  assert.deepEqual(normalizedSelection({ start: "3", end: "9" }, 10), { start: 3, end: 9 });
});

// -------------------------------------------------------------- tracker

function fakeState(text, from, to) {
  return {
    doc: { toString: () => text },
    selection: { main: { from, to } },
  };
}

test("tracker coalesces many updates into one document push", () => {
  const docs = [];
  const sels = [];
  let fn = null;
  const tracker = makeChangeTracker({
    onDocument: (t) => docs.push(t),
    onSelection: (s, e) => sels.push([s, e]),
    tick: (f) => (fn = f), // manual tick
  });

  // The browser passes a live getter (() => view); mirrors that by reading the
  // current fake state at flush time, not the state of any single update.
  let current = fakeState("h", 1, 1);
  tracker.schedule(() => current, true, true);
  current = fakeState("he", 2, 2);
  tracker.schedule(() => current, true, true);
  current = fakeState("hel", 3, 3);
  tracker.schedule(() => current, true, true);
  assert.equal(docs.length, 0, "nothing posted before tick");
  fn();
  assert.deepEqual(docs, ["hel"]);
  assert.deepEqual(sels, [[3, 3]]);
  assert.equal(tracker.flush(() => current), undefined);
});

test("tracker does not re-post unchanged text or selection", () => {
  const docs = [];
  const sels = [];
  const tracker = makeChangeTracker({
    onDocument: (t) => docs.push(t),
    onSelection: (s, e) => sels.push([s, e]),
  });
  tracker.schedule(() => fakeState("hel", 3, 3), true, true);
  tracker.flush(() => fakeState("hel", 3, 3));
  assert.deepEqual(docs, ["hel"]);
  assert.deepEqual(sels, [[3, 3]]);

  // Identical echo (Kotlin re-pushed the same text) must not be re-sent.
  tracker.schedule(() => fakeState("hel", 3, 3), true, false);
  tracker.flush(() => fakeState("hel", 3, 3));
  assert.equal(docs.length, 1);
});

test("selection-only changes post selection without a document push", () => {
  const docs = [];
  const sels = [];
  const tracker = makeChangeTracker({
    onDocument: (t) => docs.push(t),
    onSelection: (s, e) => sels.push([s, e]),
  });
  tracker.schedule(() => fakeState("hello", 1, 3), false, true);
  tracker.flush(() => fakeState("hello", 1, 3));
  assert.equal(docs.length, 0);
  assert.deepEqual(sels, [[1, 3]]);
});

test("reset forces the next push through even for identical text", () => {
  const docs = [];
  const tracker = makeChangeTracker({ onDocument: (t) => docs.push(t) });
  tracker.schedule(() => fakeState("same", 0, 0), true, false);
  tracker.flush(() => fakeState("same", 0, 0));
  assert.equal(docs.length, 1);
  tracker.reset();
  tracker.schedule(() => fakeState("same", 0, 0), true, false);
  tracker.flush(() => fakeState("same", 0, 0));
  assert.equal(docs.length, 2);
});

test("setChangeTrackerErrorHandler is wired safely", () => {
  const errs = [];
  setChangeTrackerErrorHandler((m) => errs.push(m));
  setChangeTrackerErrorHandler(undefined); // must not throw
  setChangeTrackerErrorHandler("not a function"); // must not throw
  void errs;
});
// ------------------------------------------------- autocomplete: DOCUMENTED GAP
//
// `autocompletion()` is enabled but constructed with NO options, so CodeMirror
// has no completion source to draw from and the popup never appears. That is a
// known gap, not a bug to be papered over: shipping a word list, a language
// database or a stub that "returns nothing gracefully" would be a fake feature
// pretending to be language intelligence.
//
// These tests read the bootstrap source and pin the gap in place. If a real
// completion source is ever added properly, the `no options` assertion is the
// one that is meant to fail first, so the addition cannot happen by accident.

const BOOTSTRAP = new URL("../src/editor-bootstrap.mjs", import.meta.url);
const bootstrapSource = await readFile(BOOTSTRAP, "utf8");

test("autocompletion is enabled but configured with no completion source (KNOWN GAP)", () => {
  assert.match(bootstrapSource, /\bautocompletion\(\s*\)/);

  // No `override`, no `activateOnTyping`, no source: nothing can ever be
  // suggested, so no test in this repo may claim a working popup.
  const call = bootstrapSource.match(/autocompletion\(([^)]*)\)/);
  assert.equal(call[1].trim(), "");
});

test("no language database or completion provider is wired in (KNOWN GAP)", () => {
  // Adding any of these is a feature, not a hardening fix, and needs its own
  // pass with its own device verification.
  for (const banned of [
    "@codemirror/language-data",
    "languageData",
    "lsp",
    "LanguageSupport",
  ]) {
    assert.ok(
      !bootstrapSource.includes(banned),
      `${banned} must not be imported: autocomplete is a documented gap`,
    );
  }
});
