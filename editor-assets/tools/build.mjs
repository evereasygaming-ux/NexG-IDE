// Bundles the editor sources into the Android assets directory.
//
// This is a build-time-only pipeline. Node is never a runtime dependency: the
// checked-in output under app/src/main/assets/editor/ is what the APK ships,
// and the Android build does not invoke this script.
//
// Usage:  node tools/build.mjs
// Output: app/src/main/assets/editor/{editor.js, editor.css, index.html}

import { build } from "esbuild";
import { mkdirSync, copyFileSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const srcDir = join(root, "..", "src");
const appAssets = join(root, "..", "..", "app", "src", "main", "assets", "editor");
const outDir = process.env.NEXG_ASSET_OUT || appAssets;

rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });

console.log(`building CodeMirror editor bundle -> ${outDir}`);

await build({
  entryPoints: [join(srcDir, "editor-bootstrap.mjs")],
  bundle: true,
  minify: true,
  target: ["es2020"],
  format: "iife",
  outfile: join(outDir, "editor.js"),
  sourcemap: false,
  logLevel: "info",
  define: {
    // AndroidWebView has no Node globals; be explicit that these must not leak.
    "process.env.NODE_ENV": '"production"',
  },
});

copyFileSync(join(srcDir, "editor.css"), join(outDir, "editor.css"));
copyFileSync(join(srcDir, "index.html"), join(outDir, "index.html"));

console.log("editor assets ready:", join(outDir, "editor.js"), join(outDir, "editor.css"), join(outDir, "index.html"));