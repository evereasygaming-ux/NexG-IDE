// NexG editor WebView bootstrap.
//
// Creates the CodeMirror 6 EditorView, exposes the narrow window.__NexGEditor
// bridge Kotlin drives, and posts a fixed set of JSON messages to Kotlin via
// window.android.postMessage. All decision logic lives in editor-core.mjs; this
// file only wires the view, the page and the two bridges.

import { EditorView } from "@codemirror/view";
import {
  EditorState,
  Compartment,
  Transaction,
} from "@codemirror/state";
import {
  bracketMatching,
  indentOnInput,
  indentUnit,
  syntaxHighlighting,
  HighlightStyle,
} from "@codemirror/language";
import { tags } from "@lezer/highlight";
import {
  keymap,
  lineNumbers,
  highlightActiveLine,
  highlightActiveLineGutter,
  highlightSpecialChars,
  dropCursor,
  rectangularSelection,
  crosshairCursor,
} from "@codemirror/view";
import {
  history,
  defaultKeymap,
  historyKeymap,
  indentWithTab,
} from "@codemirror/commands";
import {
  search,
  searchKeymap,
  highlightSelectionMatches,
} from "@codemirror/search";
import {
  autocompletion,
  completionKeymap,
  closeBrackets,
  closeBracketsKeymap,
} from "@codemirror/autocomplete";
import {
  makeChangeTracker,
  setChangeTrackerErrorHandler,
  languageFor,
  isKnownLanguage,
  execCommand,
  clampInt,
  encodeMessage,
  MAX_DOCUMENT_CHARS,
} from "./editor-core.mjs";

// ---------------------------------------------------------------- colours

// Mirrors ui/theme/Color.kt + ui/editor/EditorColors.kt so the WebView editor
// reads the app's palette. (Approximate by design: the app palette is the
// single source of truth; these hexes are kept in sync by hand.)
const PALETTES = {
  dark: {
    surface: "#05060A",
    plain: "#E8ECF5",
    keyword: "#7C5CFF",
    type: "#00E5FF",
    string: "#00FF94",
    number: "#FFB020",
    comment: "#9AA3B5",
    operator: "#00E5FF",
    gutter: "#9AA3B5",
    gutterActive: "#E8ECF5",
    caretLine: "rgba(255,255,255,0.08)",
    match: "rgba(255,176,32,0.20)",
    activeMatch: "rgba(255,176,32,0.40)",
  },
  light: {
    surface: "#F7F8FC",
    plain: "#0B0D14",
    keyword: "#5B3FD6",
    type: "#00899C",
    string: "#008F57",
    number: "#8F6200",
    comment: "#6B7280",
    operator: "#00899C",
    gutter: "#9AA3B5",
    gutterActive: "#0B0D14",
    caretLine: "rgba(11,13,20,0.05)",
    match: "rgba(255,176,32,0.30)",
    activeMatch: "rgba(255,176,32,0.55)",
  },
};

function highlightStyleFor(p) {
  return HighlightStyle.define([
    { tag: tags.keyword, color: p.keyword },
    { tag: [tags.typeName, tags.className, tags.namespace, tags.tagName], color: p.type },
    { tag: [tags.string, tags.special(tags.string)], color: p.string },
    { tag: [tags.number, tags.bool, tags.null], color: p.number },
    { tag: [tags.comment, tags.blockComment, tags.lineComment], color: p.comment, fontStyle: "italic" },
    { tag: [tags.operator, tags.logicOperator, tags.compareOperator], color: p.operator },
    { tag: [tags.punctuation], color: p.comment },
    { tag: [tags.attributeName, tags.propertyName], color: p.type },
    { tag: [tags.special(tags.name)], color: p.keyword },
  ]);
}

function viewThemeFor(p) {
  const dark = p === PALETTES.dark;
  return [
    EditorView.theme(
      {
        "&": {
          backgroundColor: p.surface,
          color: p.plain,
          height: "100%",
          fontSize: "13px",
        },
        ".cm-content": { caretColor: p.plain },
        ".cm-cursor, .cm-dropCursor": { borderLeftColor: p.plain },
        "&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection": {
          backgroundColor: p.activeMatch,
        },
        ".cm-gutters": {
          backgroundColor: p.surface,
          color: p.gutter,
          border: "none",
        },
        ".cm-activeLineGutter": { backgroundColor: "transparent", color: p.gutterActive },
        ".cm-activeLine": { backgroundColor: p.caretLine },
        ".cm-searchMatch": { backgroundColor: p.match, outline: "none" },
        ".cm-searchMatch-selected": { backgroundColor: p.activeMatch },
        ".cm-tooltip": {
          backgroundColor: p.surface,
          color: p.plain,
          border: `1px solid ${p.gutter}`,
        },
        ".cm-tooltip-autocomplete ul li[aria-selected]": {
          backgroundColor: p.caretLine,
        },
        ".cm-scroller": {
          fontFamily: "monospace",
          lineHeight: "1.55",
          overscrollBehavior: "contain",
        },
      },
      { dark },
    ),
    syntaxHighlighting(highlightStyleFor(p)),
  ];
}

// ------------------------------------------------------------------ bridge

const android = window.android;

function post(json) {
  try {
    if (android && typeof android.postMessage === "function") android.postMessage(json);
  } catch {
    // Bridge torn down mid-teardown; nothing to deliver to.
  }
}

function postError(message) {
  post(encodeMessage("editorError", { message: String(message) }));
}

setChangeTrackerErrorHandler(postError);

const tracker = makeChangeTracker({
  onDocument: (text) => post(encodeMessage("documentChanged", { text })),
  onSelection: (start, end) => post(encodeMessage("selectionChanged", { start, end })),
  tick: (fn) => requestAnimationFrame(() => fn()),
});

// The editor document is the single mutable thing in the WebView; every
// command that changes it goes through this.
let view;

const languageCompartment = new Compartment();
const readOnlyCompartment = new Compartment();
const themeCompartment = new Compartment();

const extensions = [
  lineNumbers(),
  highlightActiveLineGutter(),
  highlightSpecialChars(),
  history(),
  EditorState.allowMultipleSelections.of(true),
  indentUnit.of("    "),
  indentOnInput(),
  bracketMatching(),
  closeBrackets(),
  autocompletion(),
  highlightActiveLine(),
  highlightSelectionMatches(),
  rectangularSelection(),
  crosshairCursor(),
  dropCursor(),
  search(),
  keymap.of([
    ...closeBracketsKeymap,
    ...completionKeymap,
    ...searchKeymap,
    ...historyKeymap,
    ...defaultKeymap,
    indentWithTab,
    {
      key: "Mod-s",
      run: () => {
        post(encodeMessage("saveRequested", {}));
        return true;
      },
    },
  ]),
  languageCompartment.of([]),
  readOnlyCompartment.of([]),
  themeCompartment.of(viewThemeFor(PALETTES.dark)),
  EditorView.updateListener.of((update) => {
    if (update.docChanged || update.selectionSet) {
      tracker.schedule(
        () => view,
        update.docChanged,
        update.selectionSet,
      );
    }
  }),
];

view = new EditorView({ parent: document.getElementById("editor"), extensions });

// The window.__NexGEditor bridge: a closed set of methods Kotlin may call.
// No arbitrary expression evaluation is exposed — each method is fixed, and
// each argument is validated before use.
window.__NexGEditor = Object.freeze({
  setDocument(text) {
    if (typeof text !== "string" || text.length > MAX_DOCUMENT_CHARS) {
      postError("setDocument rejected: text is not a string or exceeds the bridge limit");
      return;
    }
    view.dispatch({
      changes: { from: 0, to: view.state.doc.length, insert: text },
      annotations: [Transaction.addToHistory.of(false)],
    });
  },

  setLanguage(id) {
    if (!isKnownLanguage(id)) {
      postError(`setLanguage rejected: unknown language id ${String(id)}`);
      return;
    }
    view.dispatch({
      effects: languageCompartment.reconfigure(languageFor(id) || []),
    });
  },

  setReadOnly(flag) {
    const ro = Boolean(flag);
    view.dispatch({
      effects: readOnlyCompartment.reconfigure(ro ? EditorState.readOnly.of(true) : []),
    });
  },

  setTheme(mode) {
    const p = mode === "light" ? PALETTES.light : PALETTES.dark;
    view.dispatch({ effects: themeCompartment.reconfigure(viewThemeFor(p)) });
  },

  requestFocus() {
    view.focus();
  },

  setSelection(start, end) {
    const s = clampInt(start, 0, 0, view.state.doc.length);
    const e = clampInt(end, s, 0, view.state.doc.length);
    const anchor = Math.min(s, e);
    const head = Math.max(s, e);
    view.dispatch({
      selection: head === anchor
        ? { anchor, scrollIntoView: true }
        : { anchor, head, scrollIntoView: true },
    });
  },

  runCommand(name) {
    execCommand(view, name);
  },
});

// --------------------------------------------------------------- ready signal

// The page is fully interactive (view constructed, bridge attached). Kotlin
// withholds document pushes until this arrives.
post(encodeMessage("editorReady", {}));