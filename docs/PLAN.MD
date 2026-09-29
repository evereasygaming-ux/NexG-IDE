# NexG IDE — Master Plan (Requirements, Architecture, UI, Execution)

> Status: **PLANNING** — abhi koi code nahi likha, koi build nahi chalaya.
> Ye plan `OPENCODE.MD` (memory) ke saath kaam karta hai. Memory = kya hua. Ye plan = kya banega.
> Documented: 2026-09-29

---

# PART A — ENVIRONMENT REALITY CHECK

Ye sab commands se verified hai. Andaza nahi.

## A.1 Kya MILA (green)

| Cheez | Value | Kaam karega? |
|---|---|---|
| Java | OpenJDK 21.0.12 (Termux) | ✅ |
| Gradle (system) | 9.8.0 (Kotlin 2.4.10) | ✅ (wrapper use karenge) |
| git | 2.53.0 | ✅ |
| Android SDK | `/data/data/com.termux/files/home/android-sdk` | ✅ |
| SDK platforms | `android-34`, `android-36` | ✅ → **compileSdk 36** |
| SDK build-tools | `34.0.0`, `35.0.0`, `36.0.0` | ✅ |
| Network | Google Maven 200, Maven Central 200, Plugin Portal 200 | ✅ |
| Ubuntu | proot-distro container `ubuntu`, Ubuntu 26.04.1 LTS | ✅ (Termux se launch hota hai) |
| OpenCode | `1.18.33` + **`opencode serve`** subcommand | ✅ **real HTTP API** |
| Node | v24.18.0 | ✅ |
| proot-distro | installed | ✅ |

## A.2 Kya NAHI mila / constraints (red)

| Problem | Impact | Solution |
|---|---|---|
| **`termux-api` NOT installed** | App Termux ko direct command nahi bhej sakta | App detect karega + setup guide dikhayega. Terminal/Ubuntu features graceful-degrade. AI/Server se chalta rahega. |
| **Main session proot ke andar hai** | Main yahan se `proot-distro login` nahi chala sakta | Expected. App Termux ko call karega → woh Ubuntu launch karega. Test manually. |
| **RAM sirf 1.0 GiB free** (5.4 total) | Gradle/Kotlin daemon OOM ho sakta hai | `org.gradle.jvmargs=-Xmx1024m`, `kotlin.daemon.jvmargs=-Xmx768m`, parallel off, `org.gradle.daemon` on but single worker |
| **Disk** | 66 GB free | ✅ koi dikkat nahi |
| `ANDROID_HOME` empty | SDK path nahi pata | `local.properties` mein `sdk.dir` (git-ignored) |

## A.3 Runtime chain (reality)

```
Android app "NexG IDE"          ← yeh banayenge
   │
   ├─ SAF (Storage Access Framework)  → files/projects  [NO root, NO Termux needed] ✅
   │
   ├─ HTTP 127.0.0.1:4096             → AI (opencode serve)          ✅
   │
   └─ Intent RUN_COMMAND → Termux      → shell + Ubuntu  [needs Termux:API] ⚠️
                                            ↓
                                        proot-distro login ubuntu
                                            ↓
                                          opencode serve
```

**Key design insight:** AI ko Termux ki zaroorat nahi — `opencode serve` HTTP deta hai.
Sirf *terminal* aur *Ubuntu* ko Termux chahiye. Isse app 90% kaam bina Termux ke karta hai.

---

# PART B — REQUIREMENTS (poora, checkbox form)

## B.1 Functional requirements

### R1. UI Layer — 9 screens
| # | Screen | Must have |
|---|---|---|
| 1 | **Home** | app status, backend status (Termux/Ubuntu/OpenCode), recent projects, recent files, quick actions |
| 2 | **Projects** | list, add/remove (SAF tree), refresh, open |
| 3 | **Workspace** | file tree + editor + AI panel, split on tablet, tabs on phone |
| 4 | **File Explorer** | browse, create file/folder, rename, delete, refresh, breadcrumbs |
| 5 | **Code Editor** | see R3 |
| 6 | **Terminal** | sessions, input, output, clear, restart, multi-session |
| 7 | **AI Assistant** | chat, tool/action state, changed files, diff, errors, approval |
| 8 | **Voice Assistant** | mic, waveform, state machine, transcript, command confirmation |
| 9 | **Settings** | see R7 |

### R2. Project/File management
- project root add/remove, persist across restarts
- create file / create folder / rename / delete / copy / move
- **sab async** (Dispatchers.IO), main thread block nahi
- recent projects + recent files (with timestamps)
- refresh, sorted listing, hidden-file toggle
- SAF tree URI persist + re-permission on restart

### R3. Code Editor
- syntax highlighting: **Kotlin, Java, JS, TS, Python, XML, JSON, YAML, Markdown, Shell**
- line numbers, cursor, selection
- copy / paste, undo / redo, search / replace (regex optional)
- save, unsaved indicator (`●`)
- large-file protection: >2 MB read-only warning (perf rule)
- modular: `LanguageDefinition` interface → naye language add karna = 1 file

### R4. Terminal
- multiple sessions, each with own buffer
- input with history (Up/Down)
- output auto-scroll, ANSI colours
- clear, restart session
- `Ctrl+C` / interrupt button
- **real output only** — koi fake nahi

### R5. Termux integration
- detect installed (package manager check + probe)
- config: termux path, shell command, working dir
- not installed → `"Termux is required for local terminal integration."` + setup steps
- **har path pehle check kare, hardcode assume nahi**

### R6. Ubuntu integration
- configurable command (default concept `proot-distro login ubuntu`)
- `[ Start Ubuntu ]` `[ Open Ubuntu Terminal ]` `[ Stop Session ]`
- live session status: IDLE / STARTING / RUNNING / ERROR
- distro list read from `proot-distro list` (name assume nahi)

### R7. OpenCode integration
- **Mode B (preferred):** `opencode serve` HTTP client → `http://127.0.0.1:4096` (configurable)
  - health check, session create, message send, SSE event stream
- **Mode A:** launch from terminal (`opencode <project>`)
- endpoint configurable, no hardcoded key
- auth token → **EncryptedSharedPreferences (Keystore)**, source mein nahi

### R8. AI Assistant
- chat UI: user msg, assistant msg, tool/action state, changed files, diff, error, completion
- flow: understand → inspect project → propose → show affected files → diff → **approval** → execute → report
- "AI service unavailable." message when offline (no crash)

### R9. Voice Assistant
- pipeline: Mic → SpeechRecognizer → IntentParser → CommandRouter → executor → TTS
- states: `IDLE, LISTENING, PROCESSING, CONFIRMING, EXECUTING, SPEAKING, ERROR`
- **invalid transitions rejected deterministically** (pure Kotlin, unit-tested)
- 17 intents (list below)
- extensible: `VoiceIntent` interface + registry (no giant if/else)

**Intents:** Open project, Open file, Create file, Create folder, Run project, Open terminal, Start Ubuntu, Open OpenCode, Ask AI, Explain code, Fix error, Search project, Build project, Stop command, Save file, Close project, Show recent projects.

### R10. Safety / security
- destructive command detection: `rm -rf`, `rm`, `format`, `dd`, `mkfs`, `chmod`/`chown` on system paths, `pkg uninstall`, network config (`ifconfig`, `iptables`), credential files, `su`, `reboot`, `curl|sh`
- confirmation dialog: command + `[ CANCEL ] [ CONFIRM ]`
- voice se bhi — voice confirmation bypass nahi karta
- operation log (category-wise), secrets redacted

### R11. Settings & persistence
- theme (dark-first), font size, editor settings, terminal settings
- voice settings + speech language
- OpenCode endpoint, Ubuntu command, default project dir
- confirmation behaviour (always / destructive-only / off-with-warning)
- AI settings
- DataStore (settings) + Room (projects, recents, AI history)
- secrets → EncryptedSharedPreferences

### R12. Offline behaviour
- file browse / edit / project mgmt / terminal UI → **fully usable offline**
- AI → `"AI service unavailable."`, no crash
- network check before AI calls

### R13. Error handling
- har external op: `loading / success / failure / retry` sealed class
- **no bare `catch(Exception) {}`** — typed catches + logged + user message
- structured logger, categories: UI, VOICE, AI, OPENCODE, TERMUX, UBUNTU, TERMINAL, FILES, SECURITY

## B.2 Non-functional

| Category | Requirement |
|---|---|
| Architecture | layered (ui / app / integration / safety), no monolithic Activity |
| Language | Kotlin, Compose, Material 3, Coroutines + StateFlow, ViewModel, Navigation |
| Performance | no main-thread IO, no unnecessary recomposition, no infinite polling |
| Accessibility | contentDescription, 48dp touch targets, dynamic font scale |
| Security | no hardcoded secrets, no secret logging, permission respect |
| Testability | core logic unit-testable **without Android framework** (pure Kotlin) |
| Build | green `assembleDebug` per phase |

---

# PART C — ARCHITECTURE

## C.1 Layers

```
┌───────────────────────────────────────────────┐
│ UI          Compose screens, ViewModels,      │
│             UiState (immutable data classes)  │
├───────────────────────────────────────────────┤
│ APPLICATION ProjectManager, FileManager,      │
│             TerminalManager, OpenCodeManager, │
│             VoiceManager, CommandRouter,      │
│             ConfirmationManager               │
├───────────────────────────────────────────────┤
│ DOMAIN      pure Kotlin — models, Intent      │
│             parsing, destructive detection,   │
│             VoiceStateMachine   ← unit tests  │
├───────────────────────────────────────────────┤
│ INTEGRATION termux/  ubuntu/  opencode/       │
│             (SAF, HTTP, Intent)               │
├───────────────────────────────────────────────┤
│ SAFETY      confirmation, destructive guard,  │
│             permission, operation log         │
└───────────────────────────────────────────────┘
        ↕  (arrow way nahi — neeche dekho)
   DATA  DataStore · Room · EncryptedSharedPreferences
```

**Rule:** UI → Application → Domain. Integration aur Data neeche se inject hoti hain.
Domain layer **pure Kotlin** hai (no Android imports) → fast unit tests, no Robolectric needed.

## C.2 Package structure

```
app/src/main/java/com/nexg/ide/
├── NexGApp.kt                     Application
├── MainActivity.kt                sirf NavHost + bottom bar
│
├── core/
│   ├── log/AppLogger.kt           categories + secret redaction
│   ├── result/AppResult.kt        Loading/Success/Failure sealed
│   └── dispatch/DispatcherProvider.kt
│
├── domain/                        ← PURE KOTLIN (unit tests yahan)
│   ├── model/  Project, FileNode, EditorDocument,
│   │           TerminalSession, AIMessage, AIConversation,
│   │           VoiceState, VoiceCommand, CommandExecution, AppSettings
│   ├── voice/  VoiceStateMachine, VoiceIntent, IntentParser,
│   │           IntentRegistry, CommandRouter
│   ├── security/DestructiveCommandDetector
│   └── editor/LanguageDefinition + 10 languages
│
├── data/
│   ├── local/   SettingsDataStore (Proto/DataStore),
│   │            SecurePrefs (EncryptedSharedPreferences)
│   ├── db/      Room: projects, recent_files, ai_messages
│   └── repo/    ProjectRepository, FileRepository, SettingsRepository
│
├── integration/
│   ├── saf/      SafTreeManager          (files — no root)
│   ├── termux/   TermuxDetector, TermuxClient (RUN_COMMAND)
│   ├── ubuntu/   UbuntuManager, ProotDistroClient
│   └── opencode/ OpenCodeClient (HTTP), OpenCodeServer (SSE),
│                 OpenCodeModels, OpenCodeConfig
│
├── security/
│   ├── ConfirmationManager, PermissionManager, OperationLog
│
├── terminal/
│   ├── TerminalEngine, TerminalSessionManager, AnsiParser
│
├── voice/
│   ├── SpeechRecognizer (SpeechRecognizer), TtsSpeaker,
│   │   VoiceAssistantController
│
└── ui/
    ├── theme/    Color, Theme, Type, Dimens
    ├── nav/      NavRoutes, NavGraph, BottomBar
    ├── components/ (shared: ConfirmDialog, StatusChip,
    │               FileTreeNode, CodeSurface, MessageBubble)
    └── screens/  home, projects, workspace, explorer,
                  editor, terminal, ai, voice, settings
```

## C.3 Key decisions (aur kyun)

| Decision | Choice | Reason |
|---|---|---|
| DI | **Manual (AppContainer)** | Hilt = kSP/ksp + extra build time + RAM. 1 GB RAM mein risky. Manual DI compile-safe, fast, zero extra deps. Interface + constructor injection. |
| Settings store | **Proto DataStore** | Typed, Flow-based, coroutine-native. Room zaruri nahi settings ke liye. |
| Projects/recent/AI history | **Room** | Relational + queries + migrations. |
| Secrets | **EncryptedSharedPreferences** | Android Keystore, source mein kuch nahi. |
| Editor | **Custom Compose `TextField` + `AnnotatedString`** | Full control; `BasicTextField` quirks avoid. Highlighting incremental. |
| Syntax models | **Regex-based `LanguageDefinition`** | Language server nahi, compiler nahi. Fast, dependency-free, extensible. |
| AI transport | **OkHttp + SSE** | `opencode serve` HTTP deta hai. Real API, real streaming. |
| Voice | **Android `SpeechRecognizer` + `TextToSpeech`** | No extra dep, on-device support. |
| Terminal | **Termux:API `RUN_COMMAND` intent + ServiceConnection** | Standard sanctioned path. Graceful degrade if absent. |
| Files | **SAF (`DocumentFile`)** | Android sandbox respected. No root. Works for `/sdcard/Documents`. |
| Multi-module | **Single `app` module (abhi)** | RAM/Gradle constraints. Modular package structure rakhi hai — split karna baad mein cheap hai. |

---

# PART D — UI / UX DESIGN

## D.1 Design language

- **Dark-first.** Default = dark. Light bhi available.
- Minimal, professional, developer-focused.
- No heavy shadows, no gradients, flat surfaces, 1dp hairlines.
- Accent: single highlight colour. Semantic colours sirf status ke liye
  (green=running/ok, amber=warning, red=error/destructive).
- Monospace: JetBrains Mono fallback monospace.
- Spacing scale: 4 / 8 / 12 / 16 / 24 dp
- Touch targets: minimum 48dp
- Icon + text always (icon-only sirf tab bar mein)

## D.2 Colour (dark theme)

```
background      #0D1117   primary surface
surface         #161B22   cards, panels
surfaceVariant  #21262D   elevated / input
border          #30363D   hairlines
textPrimary     #E6EDF3
textSecondary   #8B949E
accent          #58A6FF   primary action
success         #3FB950
warning         #D29922
error/danger    #F85149
```

## D.3 Main layout (phone)

```
┌─────────────────────────────────┐
│ ← Project Jessy        ⋮ ⚙      │  ← top app bar (contextual)
├─────────────────────────────────┤
│                                 │
│   PROJECT / FILE AREA           │  ← collapsible
│   (tree ya project list)        │     gesture se collapse
│                                 │
├─────────────────────────────────┤
│                                 │
│   CODE EDITOR                   │  ← main area, weight 1f
│   (line numbers + highlight)    │
│                                 │
├─────────────────────────────────┤
│   AI PANEL                      │  ← collapsible bottom sheet
│   user ⇄ assistant               │
├─────────────────────────────────┤
│  🎙 Voice  🤖 AI  ▶ Run  ⌨ Term │  ← bottom nav (4 primary)
└─────────────────────────────────┘
```

**Phone behaviour (no overcrowding):**
- Screen pe ek hi primary cheez. Editor full-screen default.
- File tree = swipe-in drawer **ya** collapsible panel (toggle button).
- AI panel = bottom sheet, draggable, 3 detents (peek / half / full).
- Terminal = full screen, editor hidden.

**Tablet / landscape behaviour:**
- `WindowSizeClass` se decide:
  - Compact (<600dp): upar wala layout
  - Medium (600–840dp): file tree permanent left rail (280dp) + editor
  - Expanded (>840dp): tree rail (280dp) + editor (1f) + AI panel right (360dp)

## D.4 Screen-by-screen

**1. Home**
- App name + logo mark
- **Backend status card** (live, poll 5s sirf jab screen open):
  Termux ● / Ubuntu ● / OpenCode ● with tap → detail
- Quick actions grid (2 col): New Project, Open Terminal, Ask AI, Voice
- Recent projects (max 5) + Recent files (max 5)
- Offline banner agar AI unavailable

**2. Projects**
- List with path, last opened, file count
- FAB `+` → SAF tree picker ("Add folder")
- Swipe/long-press → rename / remove / set-as-default
- Empty state: clear CTA

**3. Workspace** (the main screen)
- Top: project name, file breadcrumb, save state (`●` unsaved)
- Body: per D.3
- Bottom sheet: AI panel
- Bottom bar: 4 actions
- `⋮`: Run, Build, Search, Close project

**4. File Explorer**
- Breadcrumb path, back
- Sort: name / size / date
- Tap folder → open; tap file → open in editor
- Long-press → rename, delete, copy, properties
- FAB: new file / new folder
- Create/rename → dialog with validation (invalid name reject with reason)

**5. Code Editor**
- Line number gutter (tap to jump — search-based, simple)
- Current line highlight
- Search bar (top, collapsible): find + replace + match count
- Bottom status: `Ln 12, Col 5  •  Kotlin  •  UTF-8  •  LF  •  ● unsaved`
- Undo/redo buttons in overflow (Compose `TextFieldValue` supports natively)
- Read-only banner for big files

**6. Terminal**
- Session tabs (horizontal scroll) + `+` new session
- Output area: monospace, ANSI colours, auto-scroll w/ "jump to bottom"
- Input row: `TextField` + send + `Ctrl+C`
- Top bar: clear, restart, settings
- If Termux missing: full-screen state with setup instructions

**7. AI Assistant**
- Message list:
  - user: right-ish bubble, accent border
  - assistant: full width, markdown-ish
  - tool/action: collapsible card with status (running ✓ / error ✗)
  - changed files: tappable chips → diff view
  - diff: red/green lines
- Input: multiline + send
- Approval card inline (Approve / Reject)
- States: connecting / streaming / done / error / unavailable

**8. Voice Assistant**
- Big mic button (centre), pulsing ring when LISTENING
- Live waveform (amplitude from `SpeechRecognizer` RMS)
- State chip: IDLE / LISTENING / PROCESSING / CONFIRMING / EXECUTING / SPEAKING / ERROR
- Live transcript (partial + final)
- Understood command + parsed intent chip
- **Confirmation sheet** for destructive
- Result card + TTS replay button

**9. Settings**
Sections: Appearance · Editor · Terminal · Voice & Speech · AI & OpenCode
· Ubuntu · Projects · Security · About

## D.5 Navigation

Sealed `NavRoutes`:
```
HOME, PROJECTS, WORKSPACE(projectUri), EXPLORER(projectUri),
EDITOR(projectUri, fileUri), TERMINAL, AI, VOICE, SETTINGS
```
- Bottom bar: HOME · PROJECTS · TERMINAL · AI
- FAB/quick: VOICE overlay (koi bhi screen se)
- Back: standard `NavController` + `BackHandler` for editor (unsaved → 3-option dialog)
- Deep-ish safety: leaving EDITOR with unsaved changes → **Save / Discard / Cancel**

---

# PART E — BUILD CONFIG PLAN

## E.1 Version matrix (locally compatible)

| Component | Version | Why |
|---|---|---|
| Gradle wrapper | **8.14.x** (pin) | AGP 8.x compatible. System Gradle 9.8.0 **wrapper mein use nahi karenge** — AGP 8 + Gradle 9 pair nahi hota. |
| AGP | **8.7.x** | compileSdk 36 support (8.7+). Conservative. |
| Kotlin | **2.0.21** | Compose compiler plugin bundled (2.0+). AGP 8.7 ke saath proven. |
| Compose BOM | **2024.10.01** | Stable set. |
| compileSdk / targetSdk | **36** | Available (android-36) → Android 16 |
| minSdk | **26** | Termux needs 7+, but 26 = modern APIs + wide reach |
| JVM target | **17** | AGP 8 + JDK 21 toolchain compatible |

> ⚠️ Ye matrix **Phase 1 mein actually resolve karke verify hoga.** Agar combination fail kare to
> versions adjust karenge — blindly upgrade nahi. Gradle wrapper 8.14 + AGP 8.7 + Kotlin 2.0.21
> ek known-good combination hai; agar download/resolution fail ho to report + adjust.

## E.2 RAM tuning (mandatory — 1 GB free)

```properties
org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8
org.gradle.parallel=false
org.gradle.caching=true
org.gradle.configuration-cache=false
kotlin.daemon.jvmargs=-Xmx768m
kotlin.incremental=true
android.useAndroidX=true
android.nonTransitiveRClass=true
org.gradle.daemon=true
```

## E.3 Dependencies (minimal, justified)

```
androidx.core:core-ktx
androidx.lifecycle:lifecycle-runtime-ktx, lifecycle-viewmodel-compose
androidx.activity:activity-compose
androidx.navigation:navigation-compose
compose: BOM → ui, material3, material-icons-extended, foundation, ui-tooling-preview
androidx.datastore:datastore (settings)
androidx.room: room-runtime, room-ktx + KSP (projects, recents, AI history)
androidx.security:security-crypto (secrets)
com.squareup.okhttp3:okhttp (OpenCode HTTP + SSE)
org.jetbrains.kotlinx:kotlinx-coroutines-android
test: junit4, kotlinx-coroutines-test, robolectric(minimal), truth/hamcrest
```

**Deliberately nahi:** Hilt/Dagger, Retrofit (OkHttp kaafi), extra DataStore layer,
Coil (abhi image nahi), koi bhi fake/mock runtime library.

## E.4 Verification gate per phase

Har phase ke baad:
```
./gradlew :app:assembleDebug   →  BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest → ALL TESTS PASSED
```
Dono green ke bina phase complete nahi.

---

# PART F — PHASE EXECUTION PLAN

| Phase | Scope | Exit gate |
|---|---|---|
| **0** ✅ | Inspect, cleanup, memory file, git | DONE |
| **1** | Gradle scaffold, `NexGApp`, `MainActivity`, Material3 theme, NavHost + 4 bottom routes (placeholder screens sirf routing ke liye, core logic nahi), `AppLogger`, `AppResult`, DispatcherProvider | assembleDebug green |
| **2** | SAF layer, models, Room, `ProjectManager`, `FileManager` (CRUD async), recents | green + ProjectManager/FileManager tests |
| **3** | Editor: `LanguageDefinition` × 10, `CodeSurface`, gutter, search/replace, save/unsaved, buffers | green + editor tests |
| **4** | `TerminalEngine` + session manager + ANSI parser + UI (backend adapter Termux se baad mein) | green + engine tests |
| **5** | Termux detect + `RUN_COMMAND` client + setup screen + config | green + detector tests |
| **6** | Ubuntu: detect distro, start/stop, status, config | green + manager tests |
| **7** | OpenCode: HTTP client, health, session, SSE stream, config, encrypted token | green + client tests (MockWebServer) |
| **8** | AI workspace UI: messages, tool state, diff, approval, offline handling | green |
| **9** | Voice: state machine, intent parser + 17 intents, router, SpeechRecognizer, TTS, UI | green + VoiceStateMachine/IntentParser/CommandRouter tests |
| **10** | `DestructiveCommandDetector`, `ConfirmationManager`, permission handling, operation log | green + detector tests (heavy coverage) |
| **11** | Settings DataStore + all sections + persistence | green + settings tests |
| **12** | Full test run, UI polish, accessibility pass, release-build check, final APK | assembleDebug + all tests green |

**Phase 1 mein deliver karunga:**
`settings.gradle.kts`, `build.gradle.kts` (root), `app/build.gradle.kts`, `gradle.properties`,
`gradle/libs.versions.toml`, wrapper, `AndroidManifest.xml`, `proguard-rules.pro`,
res (themes/strings/colors/icons), aur poora `ui/theme` + `core` + `nav` skeleton.

---

# PART G — RISKS

| Risk | Severity | Mitigation |
|---|---|---|
| **Termux:API nahi hai** | HIGH for Terminal/Ubuntu | App detect + guide. AI/Editor/Files poora kaam karega. User ko `pkg install termux-api` + Termux:API app + "Allow external apps" enable karna hoga. |
| **1 GB RAM → OOM** | HIGH | E.2 tuning; `org.gradle.workers.max=1`; agar OOM to `-Xmx768m` + `kotlin.compiler.execution.strategy=in-process` |
| AGP/Kotlin/Gradle version clash | MEDIUM | Known-good matrix; Phase 1 mein resolve karke verify; fail → adjust + report |
| `/sdcard` pe Gradle build slow | MEDIUM | Build output `/sdcard` pe hi (project wahi hai). Agar slow to Gradle project cache `~` mein. |
| SAF slow on deep trees | LOW | Caching + `Dispatchers.IO` + pagination for >500 entries |
| OpenCode server start na ho | MEDIUM | Clear "AI service unavailable" + retry + setup hint in Settings |
| SpeechRecognizer on some ROMs weak | LOW | Manual text input fallback ALWAYS available |

---

# PART H — REQUIRED EXTERNAL SETUP (user se)

1. `pkg install termux-api` + **Termux:API app** Play Store se install
2. Termux → Settings → **"Allow external apps"** enable
3. `opencode serve --port 4096 --hostname 127.0.0.1` chalana (ya app se — Mode A)
4. `local.properties` mein `sdk.dir` (main likh dunga, git-ignored)
5. Optional: voice ke liye device IME with speech support

---

**Next:** Phase 1 — Android foundation. Build tab chalega jab aap kahein.
