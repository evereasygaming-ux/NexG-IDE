# NexG IDE — Master Architecture & Implementation Plan

> **Status:** PLANNING — zero source code exists. 4 doc commits, 0 `.kt` files.
> **Single source of truth.** Merge hua hai: original master plan + user-provided
> NEXG IDE architecture spec + Gemini pivot. Conflicts explicitly resolved in Part 2.
> Updated: 2026-09-29

---

# PART 0 — REPOSITORY AUDIT (evidence, not assumption)

Command: `git log --oneline --stat`, `git ls-files`, `find -name '*.kt'`

```
13f2902  docs: memory Entry 003 — master plan + 3 verified env problems
b1b9bf9  docs: add comprehensive master PLAN.MD          (534 lines)
c6406d5  docs: update project memory with git setup
22a5593  chore: init NexG IDE repository with project memory
```

| Item | Result |
|---|---|
| Commits | 4 (all documentation) |
| Tracked files | `.gitattributes`, `.gitignore`, `OPENCODE.MD`, `PLAN.MD`, `REPORT.MD` |
| File location | `PLAN.MD` moved `docs/` → repo root on 2026-09-29 (owner asked); git tracked it as a rename, history intact |
| Source files (`.kt`/`.java`/`.gradle`) | **0** |
| Working tree | clean |
| Git identity | `NexG IDE <nexg-ide@localhost>` |

**Conclusion: nothing was implemented. Nothing can be "preserved" except decisions.**
This is an advantage — the architecture is still free to change at zero cost.

### Preserved from the original plan (valid, keep)

| Decision | Where | Keep? |
|---|---|---|
| Layered arch: ui / app / domain / integration / safety | old `:175` | ✅ |
| `domain/` = **pure Kotlin**, no Android imports → fast tests, no Robolectric | old `:216` | ✅ |
| Manual DI (`AppContainer`), **no Hilt** — RAM + kSP cost | old `:261` | ✅ |
| Single `app` module, modular package layout | old `:271` | ✅ |
| Secrets → `EncryptedSharedPreferences` (Keystore) | old `:264` | ✅ |
| Custom Compose `TextFieldValue` editor + regex `LanguageDefinition` | old `:265` | ✅ |
| `.gitattributes` LF normalization (sdcard is FAT-family) | `OPENCODE.MD:165` | ✅ |
| `core.filemode=false`, `safe.directory` on **symlink-resolved** path | `OPENCODE.MD:167-172` | ✅ trap already hit once |
| 48dp touch targets, no main-thread IO, typed catches only | old `:159-170` | ✅ |
| Unit-testable core: VoiceStateMachine, IntentParser, CommandRouter, detector | old `:126` | ✅ |
| JDK 21 → JVM target 17, compileSdk/targetSdk 36, minSdk 26 | old `:433-435` | ✅ |
| Known-good version matrix | old `:429-431` | ⚠️ **needs fix — see Part 2, C4** |

---

# PART 1 — PRODUCT VISION

NexG IDE is a **mobile-first Android IDE**. It must work on a phone, without a PC.

**Two target users exist and they need different products.** Resolving this is the
single most important decision in this document.

### Tier 1 — "Zero-setup consumer" (Play Store, general user)

User installs the app and **does nothing**. No Termux, no Linux, no API key hunting,
no boot scripts, no configuration.

```
User speaks: "Ek music player banao, dark theme mein"
     ↓
App → Gemini API (Google's cloud) → generated HTML/CSS/JS
     ↓
App → WebView renders it live on the phone
     ↓
User speaks: "Ab purple accent"  → regenerates instantly
```

This tier is the one with growth potential. It is the tier the user asked for
("user kuchh karna na pade").

### Tier 2 — "Full-power local" (sideload, power users, the owner)

The full original vision: a real Android development workstation on the phone.
Project files, Gradle builds, real APKs, Logcat, Git, a real Linux terminal.

```
NexG IDE → Termux → Ubuntu → OpenCode / Gradle / Android SDK
```

This tier is genuinely useful but **cannot ship to Play Store** (Part 2, C1).

**Both tiers ship from one codebase.** The difference is which adapter is active,
not which code exists. This is the central architectural commitment of this plan.

---

# PART 2 — CONFLICTS & RESOLUTIONS

Identified during audit, per the task's "identify conflicts before removing them" rule.

## C1 — 🔴 The spec assumes an execution layer that cannot reach Play Store

**Spec says:** "using the existing Termux + Ubuntu + OpenCode development
environment", "Termux/Ubuntu/OpenCode remain the development execution layer."

**Verified evidence:**

| Finding | Command / result |
|---|---|
| Termux not on Play Store | removed 2021; F-Droid/GitHub only |
| `termux-api` binary absent | `ls: cannot access '.../termux-api': No such file` |
| OpenCode is glibc | `readelf -d` → `NEEDED libc.so.6`, `ld-linux-aarch64.so.1` |
| OpenCode binary size | **177 MB** (Play Store base module limit ~200 MB) |
| proot inside an app | release builds are non-debuggable → kernel blocks `ptrace` → proot fails (**must be verified in Phase 1**) |

Termux is **bionic**; OpenCode needs **glibc**. They cannot be reconciled on-device.

**Resolution:** the spec's architecture is **preserved as Tier 2**, not deleted.
It becomes a *pluggable adapter* instead of a hardcoded assumption. Play Store
users get Tier 1, which needs none of it.

## C2 — 🔴 The spec contradicts the owner's most recent direction

**Spec §9:** OpenCode is the AI layer.
**Owner (2026-09-29, most recent):** "hum gemini api ka system rakhenge", after
stating the goal is users who do nothing.

**Resolution:** neither wins. The spec has no seam for swapping the AI provider, and
the Gemini pivot is cloud-only. Introduce `AiBackend` as an interface **from Phase 4**.
`GeminiBackend` is the default; `OpenCodeBackend` ships in Phase 12. No UI code
changes when a backend is added.

## C3 — 🟡 Phase ordering blocks 5 of 13 phases on day one

The spec's Phase 0→12 runs Terminal (4) → Build (5) → Logs (6) → OpenCode (8) →
Git (10) consecutively. **All five require `termux-api`, which is not installed.**

**Resolution:** reorder. Every phase that SAF or HTTPS alone can satisfy is moved
before the first Termux-dependent phase. The Play Store-capable app becomes usable
far earlier, and Termux setup becomes an optional upgrade rather than a blocker.

## C4 — 🟡 The original version matrix is internally contradictory

`old :430` pairs **AGP 8.7.x with compileSdk 36**. AGP 8.7 tops out at compileSdk 35.
Gradle 8.14 + Kotlin 2.0.21 is also not the matching set for AGP 8.9+.

**Resolution:** corrected matrix in Part 7. **Verify by actual resolution in Phase 1** —
do not trust the table blindly.

## C5 — 🟢 "Do not expose credentials" is stated but never solved

Spec §9 forbids exposing keys but defines no acquisition strategy.

**Resolution:** **Bring-Your-Own-Key (BYOK)** for Tier 1. The user pastes their own
Gemini key into a Keystore-backed screen. Zero cost to the owner, zero key-extraction
risk, no server. A `CloudProxyBackend` may be added later without changing callers.

> Never ship a working API key inside the APK. It is extractable in minutes and is a
> Play Store policy violation.

## C6 — 🟢 The spec is silent on a build-host RAM ceiling

Verified on this device: **5.4 GiB total, 1.6 GiB available, 3.8 GiB swap free.**
A default Gradle + Kotlin daemon pair wants ~2.5 GiB. Untreated, the build swaps or dies.

**Resolution:** mandatory `gradle.properties` in Part 7, written in Phase 1.

---

# PART 3 — CORE ARCHITECTURE (merged)

The spec's diagram is kept. The change is that **three boundaries become interfaces**,
so Tier 1 and Tier 2 share one application.

```
                        ┌────────────────────────────┐
                        │        NEXG IDE (app)      │
                        │  Compose UI + Navigation  │
                        └─────────────┬──────────────┘
                                      │
              ┌───────────────────────┼───────────────────────┐
              │                       │                       │
        PROJECT ENGINE          AI ASSISTANT              TERMINAL UI
              │                       │                       │
              └───────────────────────┼───────────────────────┘
                                      │
   ═══════════════════ PORT BOUNDARIES (interfaces) ═══════════════════
                                      │
      ┌───────────────────┬───────────┴──────────┬──────────────────┐
      │                   │                      │                  │
 FileSystemPort      ExecutorPort           AiBackend        BuildPort
      │                   │                      │                  │
 ┌────┴─────┐       ┌─────┴──────┐        ┌──────┴───────┐    ┌─────┴─────┐
 │ SafFs    │       │ TermuxExec │        │ GeminiBackend│    │ GradleTask│
 │ (no root)│       │  (RUN_CMD) │        │ OpenCodeBack.│    │ runner    │
 └──────────┘       │  (Tier 2)  │        │ (Tier 2)     │    └───────────┘
                    └────────────┘        └──────────────┘
 TIER 1 ── only SafFs is used                        TIER 2 ── all ports active
 TIER 1 needs: SAF permission + HTTPS. Nothing else.  TIER 2 adds: Termux + Ubuntu
```

**Rule:** UI → Application → Domain. Ports live in `domain/`. Adapters live in
`integration/`. `domain/` stays **pure Kotlin** — that is what keeps the core unit
testable without an emulator, and it is preserved from the original plan.

## Package structure

```
app/src/main/java/com/nexg/ide/
├── NexGApp.kt                      Application + AppContainer
├── MainActivity.kt                 NavHost + bottom bar only
│
├── core/
│   ├── log/AppLogger.kt            categories + secret redaction
│   ├── result/AppResult.kt         Loading/Success/Failure sealed
│   └── dispatch/DispatcherProvider.kt
│
├── domain/                         ← PURE KOTLIN, unit tests live here
│   ├── model/      Project, FileNode, EditorDocument, TerminalSession,
│   │               AIMessage, BuildResult, LogEntry, AppSettings, VoiceState
│   ├── port/       FileSystemPort, ExecutorPort, AiBackend, BuildPort
│   ├── voice/      VoiceStateMachine, VoiceIntent, IntentParser, IntentRegistry
│   ├── security/   DestructiveCommandDetector, OperationClassifier
│   ├── diff/       DiffEngine, FileChange
│   ├── build/      GradleErrorParser
│   └── editor/     LanguageDefinition + 10 languages
│
├── data/
│   ├── local/      SettingsDataStore, SecurePrefs (EncryptedSharedPreferences)
│   ├── db/         Room: projects, recent_files, ai_messages, build_history
│   └── repo/       ProjectRepository, FileRepository, SettingsRepository
│
├── integration/
│   ├── saf/        SafFsAdapter                    ← TIER 1 core
│   ├── gemini/     GeminiBackend, GeminiStream, PromptBuilder   ← TIER 1 core
│   ├── termux/     TermuxDetector, TermuxExecutor            ← TIER 2
│   ├── ubuntu/     UbuntuManager, ProotDistroClient           ← TIER 2
│   ├── opencode/   OpenCodeBackend, OpenCodeSse               ← TIER 2
│   └── build/      GradleTaskRunner                            ← TIER 2
│
├── security/       ConfirmationManager, PermissionManager, OperationLog
├── terminal/       TerminalEngine, SessionManager, AnsiParser
├── voice/          SpeechRecognizer, TtsSpeaker, VoiceController
│
└── ui/
    ├── theme/      Color, Theme, Type, Dimens       (Part 6)
    ├── nav/        NavRoutes, NavGraph, BottomBar
    ├── components/ VoiceOrb, AuroraBackground, GlassCard, DiffView,
    │               ConfirmDialog, StatusChip, PreviewPane
    └── screens/    home, projects, workspace, explorer, editor,
                    terminal, build, logs, ai, voice, settings
```

---

# PART 4 — COMPONENTS

## 4.1 Project Manager
create / open / recent / metadata / locations / templates / search /
delete-with-confirmation. Must recognise standard Android+Gradle layout
(`app/src/main/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle/wrapper/`).

## 4.2 File Explorer
Directory tree, navigation, create file/folder, rename, move, copy,
delete-with-confirmation, search, recent files.
**Protection:** refuse to delete `gradle/wrapper/`, `.git/`, `AndroidManifest.xml`,
`settings.gradle*` without elevated confirmation. Implemented in `domain/security/`
so it is unit-testable, not in a ViewModel.

## 4.3 Code Editor
Highlighting, line numbers, selection, copy/paste, undo/redo, find, replace,
go-to-line, tabs, unsaved indicator (`●`), auto-indent, bracket matching,
large-file guard (>2 MB read-only), completion *architecture* (interface only, not
a full LSP — that is a desktop-class problem).

**Languages, priority order (spec §3):** Kotlin → Java → XML → Gradle → JSON →
Markdown → Shell. Extensible via `LanguageDefinition`; adding one is one file.

## 4.4 Terminal (Tier 2)
Talks to the existing Termux environment. **Does not recreate a Linux userspace.**
Input, stdout/stderr streaming, process state, history, working directory,
stop/cancel, copy output, clear. Always shows when a command is executing.
Degrades to a setup screen when `termux-api` is absent — never a fake shell.

## 4.5 Ubuntu Integration (Tier 2)
Works with the **existing** Ubuntu. Does not create a new one.
Status: IDLE / STARTING / RUNNING / ERROR. Distro list read from `proot-distro list`.

## 4.6 Build Engine (Tier 2)
Gradle sync/status, `assembleDebug`, `assembleRelease`, arbitrary task selection,
output streaming, progress, **APK discovery**, build history.
`GradleErrorParser` (domain) extracts structured errors from raw Gradle output:
Kotlin, Java, Android resource, manifest, dependency — each with file, line, message.

## 4.7 Logs (Tier 2)
Start/stop stream, clear, filter, search, severity, package filter, copy.
Designed so device debugging (logcat for a target app) can be added later.

## 4.8 AI Assistant
Project-aware. Understands structure, inspects relevant files, explains, generates,
modifies, diagnoses errors, proposes fixes, analyses Gradle errors, refactors.
**Every change is transparent:** propose → affected files → diff → approval → apply.

## 4.9 AI Backend (interface — resolves C2)

```kotlin
interface AiBackend {
    val id: String
    val requiresNetwork: Boolean
    suspend fun health(): BackendHealth
    suspend fun complete(req: AiRequest): Flow<AiEvent>   // streaming
}
```

| Backend | Tier | Transport | Notes |
|---|---|---|---|
| `GeminiBackend` | 1 | HTTPS + SSE to `generativelanguage.googleapis.com` | **default**; BYOK; multimodal; verified reachable (403 = connectivity OK, key missing) |
| `OpenCodeBackend` | 2 | HTTP `127.0.0.1:4096` | local, private, existing install |
| `CloudProxyBackend` | 1 | HTTPS to owner's server | later; no server cost today |

Context sent to the AI: project tree (depth-limited), current file, recent
conversation, and — when available — build errors. Nothing else. No secrets.

## 4.10 Safety / Approval System (spec §10, preserved)

| Class | Examples | Behaviour |
|---|---|---|
| **SAFE** | read file, search, inspect Gradle config, inspect logs | auto-run |
| **REVIEW** | edit source, create file, modify Gradle config, add dependency, run build | show what will change |
| **APPROVAL** | delete files, destructive shell, system config, package install, permission change, `git reset/clean`, mass edits | explicit confirm dialog |

Enforced in `domain/security/OperationClassifier` — pure Kotlin, no Android imports,
**100% unit-testable**. This is the safety property most worth protecting in tests,
because it is the one that must never silently regress.

Voice is subject to the same classifier. **Voice confirmation never bypasses it.**

## 4.11 Diff / Change Review (spec §11, preserved)

Every AI modification renders as a diff: `[Review] [Apply] [Reject]`.
`DiffEngine` is pure Kotlin (LCS) in `domain/diff/` — unit-testable, and reusable
for Git diffs in Phase 13.

## 4.12 Git (Tier 2)
status, diff, log, add, commit, branch, checkout, pull, push.
**Destructive ops** (`reset --hard`, `clean -fd`, force push) require explicit
confirmation and are routed through `OperationClassifier`.

---

# PART 5 — DATA FLOW (unchanged from spec §15)

```
User request → NexG IDE → assemble project context → collect files/errors
   → AiBackend → analysis → proposed changes → Diff → user approval
   → apply → Gradle build (Tier 2) → result → AI diagnosis if failed
```

## 5.1 Error-to-Fix (spec §16 — signature workflow)

```
BUILD FAILED → GradleErrorParser extracts structured error
  → AI receives: error + stack trace + relevant source + project context
  → explains → proposes fix → diff shown → approve → apply → rebuild
```

This is the highest-value loop in the product and should be demoed first.
It needs Tier 2 (a real build). For Tier 1 the same loop applies to
**preview errors and user-reported bugs**, using multimodal screenshot input.

---

# PART 6 — UI ARCHITECTURE (phone-first, not a desktop clone)

Spec §12 is retained verbatim in intent: bottom navigation, collapsible panels,
touch-friendly controls, dark/light, responsive, no clutter.

**Navigation targets:** Projects · Files · Editor · Build · AI · Logs · Settings

**Tier 2 workspace (spec §14, kept):**
```
┌─────────────────────────┐
│ Project Name       ⋮    │
├─────────────────────────┤
│ Files  (collapsible)    │
│  app/                   │
│   ├─ src/               │
│   └─ build.gradle.kts   │
├─────────────────────────┤
│ Editor (weight 1f)      │
├─────────────────────────┤
│ AI │ Terminal │ Build   │  ← persistent AI access
└─────────────────────────┘
```

**Tier 1 workspace (new — the "wow" surface):**
```
┌─────────────────────────┐
│  ◉ orb          ⚙  ⇄Tier │
├─────────────────────────┤
│                         │
│  stream / chat          │  ← AI writes here
│                         │
├─────────────────────────┤
│  📱 LIVE PREVIEW    ⤢ ↻ │  ← WebView, draggable height
├─────────────────────────┤
│ "kuch bhi bolo..."  🎤  │
└─────────────────────────┘
```
Preview height is draggable 0→100%, with a "full screen" affordance.
Iteration latency is the product: say → see → refine.

## 6.1 Visual system (owner's "futuristic" requirement)

```
base       #05060A     surface   #0E1119     border  #1E2434
primary    #7C5CFF     cyan      #00E5FF     success #00FF94
warn       #FFB020     error     #FF4D6D     text    #E8ECF5
```

| Element | Behaviour |
|---|---|
| **VoiceOrb** | centrepiece + product mark; radius pulses with mic amplitude; violet→cyan gradient |
| **AuroraBackground** | 2–3 blurred gradient blobs, ~20 s slow rotation; low opacity |
| **Streaming shimmer** | left-to-right highlight sweep over in-flight AI text |
| **GlassCard** | `blur(20dp)`, 8% white fill, 1px gradient border (top violet → transparent) |
| **Springs** | `spring(dampingRatio = 0.6)`; no linear easing on state changes |
| **Haptics** | crisp tick on listening / thinking / done / error |

## 6.2 Initial screen (spec §13)

```
NexG IDE
[ Open Project ]  [ New Project ]
Recent Projects ────────────────
AI Assistant  "How can I help?"  ← always reachable
Terminal · Build · Settings
```

## 6.3 Accessibility (inherited from original plan)
`contentDescription` on every control, 48dp minimum targets, dynamic font scale,
icon **and** text (icon-only in bottom bar only), dark default with light available.
Contrast must clear WCAG AA against the palette above — verify, do not assume.

---

# PART 7 — BUILD CONFIGURATION

## 7.1 Version matrix (CORRECTED — resolves C4)

| Component | Original | **Corrected** | Reason |
|---|---|---|---|
| Gradle wrapper | 8.14.x | **8.13** | matches AGP 8.9.x |
| AGP | 8.7.x ❌ | **8.9.2** | 8.7 cannot compile against `compileSdk 36` |
| Kotlin | 2.0.21 | **2.1.0** | required by AGP 8.9 |
| Compose BOM | 2024.10.01 | **2025.01.00** | matched Compose compiler |
| compileSdk / targetSdk | 36 | 36 | `android-36` installed |
| minSdk | 26 | 26 | |
| JVM target | 17 | 17 | JDK 21 toolchain |

> ⚠️ Resolve for real in Phase 1. If resolution fails, adjust and record the
> actual working combination in `OPENCODE.MD`. Do not guess twice.

## 7.2 `gradle.properties` (mandatory — resolves C6)

Measured on this device: 5.4 GiB total / 1.6 GiB available / 3.8 GiB swap.
A default daemon pair wants ~2.5 GiB → swap or OOM.

```properties
org.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8
org.gradle.workers.max=1
org.gradle.parallel=false
org.gradle.caching=true
org.gradle.configuration-cache=false
org.gradle.daemon=true
kotlin.compiler.execution.strategy=in-process
kotlin.incremental=true
android.useAndroidX=true
android.nonTransitiveRClass=true
```

`in-process` removes the separate Kotlin daemon JVM (~800 MiB saved).
`workers.max=1` was **missing** from the original plan and is required here.

## 7.3 Dependencies (minimal)

```
androidx.core:core-ktx
androidx.lifecycle:lifecycle-runtime-ktx, lifecycle-viewmodel-compose
androidx.activity:activity-compose
androidx.navigation:navigation-compose
compose BOM → ui, material3, material-icons-extended, foundation, ui-tooling-preview
androidx.datastore:datastore
androidx.room: room-runtime, room-ktx + KSP
androidx.security:security-crypto
okhttp                                  ← Gemini SSE + OpenCode
kotlinx-coroutines-android, kotlinx-serialization-json
test: junit4, kotlinx-coroutines-test, truth
```

**Deliberately excluded:** Hilt/Dagger (kSP + RAM), Retrofit (OkHttp suffices),
Coil (no images yet), Robolectric (domain is pure Kotlin by design).

> **Note on the original rationale:** the old plan excluded Hilt *because* of kSP/RAM.
> Room already requires KSP, so that specific argument is void. Manual DI is still the
> right call — fewer deps, faster builds — but the honest reason is now "fewer moving
> parts", not "kSP avoidance". Recorded so nobody repeats the reasoning.

## 7.4 Verification gate — every phase

```
./gradlew :app:assembleDebug      → BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest  → ALL TESTS PASSED
```

**A phase is not complete until both are green and the result is recorded in
`OPENCODE.MD`. Files created ≠ phase complete.**

---

# PART 8 — REORDERED PHASES (resolves C3)

Reordered so that **everything reachable without `termux-api` ships first**.
The Play Store-capable app is usable long before any Termux work begins.

| Phase | Scope | Needs Termux? | Gate |
|---|---|---|---|
| **0** ✅ | Audit, merge plan, repo hygiene | — | **DONE** (4 doc commits) |
| **1** | App shell, `NexGApp`, `MainActivity`, theme (Part 6.1), NavHost, 4 routes, `core/` | ❌ | assembleDebug + **verify corrected version matrix** + **test proot-in-release** |
| **2** | SAF port + adapter, ProjectManager, models, Room, Projects + Explorer screens | ❌ | green + ProjectManager/FileManager tests |
| **3** | Editor: `LanguageDefinition` ×7, `CodeSurface`, gutter, find/replace, buffers, unsaved state | ❌ | green + editor tests |
| **4** | **Ports + `GeminiBackend`** (streaming, health) + BYOK key screen | ❌ | green + backend tests (mock server) |
| **5** | AI chat screen, streaming UI, shimmer, message history | ❌ | green |
| **6** | **Code generation → WebView live preview** (Tier 1 "wow" path) | ❌ | green |
| **7** | **Safety layer + Diff + approval** — `OperationClassifier`, `DiffEngine`, diff UI, confirm dialogs | ❌ | green + classifier/diff tests (**heavy coverage**) |
| **8** | `TermuxDetector` + `ExecutorPort` adapter + setup guide screen | ✅ *install* | green + detector tests |
| **9** | Terminal UI: sessions, ANSI parser, streaming, `Ctrl+C` | ✅ | green + engine tests |
| **10** | Build engine: `GradleErrorParser`, task runner, APK discovery, build history | ✅ | green + parser tests |
| **11** | Logs + Logcat foundation | ✅ | green |
| **12** | `OpenCodeBackend` (Tier 2 AI) + `UbuntuManager` | ✅ | green + client tests |
| **13** | Git operations + destructive-op guard | ✅ | green |
| **14** | Voice: state machine, intent parser, router, SpeechRecognizer, TTS, orb UI | ❌ | green + VoiceStateMachine/IntentParser/router tests |
| **15** | Multimodal: screenshot → diagnose → fix | ❌ | green |
| **16** | Hardening: accessibility pass, large-project perf, lifecycle/failure tests, error-to-fix loop | — | full suite green |
| **17** | Release build (Play Store + sideload variants) | ✅ | signed APK + report |

**Deliverable after Phase 7:** a genuinely usable, Play Store-ready Tier 1 app —
voice/text → AI → live preview → diff → approve — with **no Termux dependency at all**.
This is the natural first milestone and first demo.

**Termux becomes an optional upgrade from Phase 8 onward**, not a prerequisite.

## 8.1 Required external setup (owner)

**Tier 1 — none.** SAF permission + BYOK key only.

**Tier 2 — required from Phase 8:**
1. `pkg install termux-api` + Termux:API app (F-Droid)
2. Termux → Settings → "Allow external apps" ON
3. `opencode serve --port 4096 --hostname 127.0.0.1` for Tier 2 AI
4. `local.properties` → `sdk.dir=/data/data/com.termux/files/home/android-sdk` (git-ignored)

---

# PART 9 — RISKS

| Risk | Severity | Mitigation | Phase |
|---|---|---|---|
| Corrected version matrix still fails to resolve | HIGH | resolve in Phase 1, record actual combo | 1 |
| `termux-api` unavailable → 6 Tier 2 phases blocked | HIGH | reordered: Tier 1 ships first (§8) | 8 |
| Gemini BYOK = friction vs. "user does nothing" | MEDIUM | accept; add `CloudProxyBackend` later | 4 |
| Build RAM ceiling (1.6 GiB avail) | MEDIUM | §7.2 tuning, `workers.max=1` | 1 |
| AI runs on a 1.6 GiB device with streaming + WebView | MEDIUM | stream to file not memory; release preview on stop | 5,6 |
| Play Store policy: BYOK is compliant, bundled key is not | MEDIUM | BYOK only; never ship a key | 17 |
| `SpeechRecognizer` weak on some ROMs | LOW | text input fallback always available | 14 |
| SAF slow on deep trees | LOW | cache, `Dispatchers.IO`, paginate >500 entries | 2 |
| proot blocked in release builds (unverified) | MEDIUM | verify in Phase 1; affects Ubuntu feature only | 1,12 |
| WebView XSS from generated HTML | MEDIUM | `setJavaScriptEnabled` required for preview; sandboxed origin; no file:// access; never render in an auth-bearing context | 6 |

---

# PART 10 — OPEN QUESTIONS

Answer before the relevant phase starts.

1. **Package name** — `com.nexg.ide`? (proposed)
2. **Which Tier is built first?** Recommendation: Tier 1 (Phases 1–7). Tier 2 follows.
3. **Is the Android-build feature (real APKs) essential?** It is Tier 2 only and
   requires Termux forever. If dropped, the Play Store story gets much stronger.
4. **Tiers as one app or two APKs?** One app with a detected capability tier is
   simpler; two APKs avoids shipping Termux code to Play Store reviewers.
5. **Git identity** — `NexG IDE <nexg-ide@localhost>` is a placeholder.
6. **Monetisation** — affects Phase 17 only. Not blocking.

---

# PART 11 — SOURCE OF TRUTH RULES

`PLAN.MD` is the master plan. `OPENCODE.MD` is the work log/memory. Both are maintained.

For every phase:
- mark status in §8
- record what was implemented, in `OPENCODE.MD`
- record the verification command output — **not** a claim
- record known limitations
- record remaining work

**Never mark a phase complete without repository evidence or a green build/test log.**
Files created ≠ work done. Planned ≠ implemented.

---

**Next action:** owner answers the Part 10 questions. Then Phase 1 begins.
**No implementation starts without explicit approval.**
