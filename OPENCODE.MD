# OPENCODE.MD — NexG IDE Project Memory

> Yeh file project ki permanent memory hai. Har kaam (ya har phase ke end) par update hoti hai.
> Isko kabhi delete mat karna. Yeh source of truth hai.

---

## 1. IDENTITY

| Field | Value |
|---|---|
| **Project name** | NexG IDE |
| **Type** | Mobile-first Android IDE — **two tiers**: Tier 1 (zero-setup, Play Store) + Tier 2 (full local, sideload) |
| **Owner** | Personal **and** general users (direction changed 2026-09-29 — was "personal use only") |
| **Location** | `/sdcard/Documents/NexG IDE` |
| **Status** | PLANNING — **0 source files**, 4 doc commits, plan merged |
| **Builds run?** | ❌ NO (user ne explicitly mana kiya hai abhi) |

> Note: Purana project **"Project Jessy"** tha. Use delete kar diya gaya hai (user ke instruction par).
> Uska backup yahan hai: `/sdcard/ProjectJessy-backup.tar.gz` (5.4 MB, gzip verified OK).
> Purane project ka package name tha `com.project.jessy` — naye project ka package decide hoga.

---

## 2. ENVIRONMENT (verified via commands)

Ye sab *actually check karke* likha gaya hai, andaza nahi.

```
Phone: Android (ARM64)
   ↓
Termux  (com.termux)
   ↓
Ubuntu via proot-distro
   ↓
OpenCode
```

| Item | Status | Detail |
|---|---|---|
| Java | ✅ OK | `openjdk 21.0.12` (Termux build, 2026-07-21) |
| Gradle (system) | ✅ OK | `9.8.0` (Kotlin 2.4.10, Groovy 4.0.33) |
| Kotlin compiler | ✅ present | `kotlinc` in PATH |
| adb | ✅ present | Termux `android-tools` |
| git | ✅ OK | `2.53.0` |
| Android SDK | ✅ found | `/data/data/com.termux/files/home/android-sdk` |
| SDK platforms | ✅ OK | `android-34`, `android-36` (android-36 = target for Android 16) |
| SDK build-tools | ✅ OK | `34.0.0`, `35.0.0`, `36.0.0` (+ `34.0.0-termux-shim`) |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | ❌ EMPTY | `local.properties` mein `sdk.dir` dena hoga |
| proot-distro | ⚠️ UNVERIFIED | `proot-distro list` ne kuch return nahi kiya |
| OpenCode | ⚠️ UNVERIFIED | `.opencode` folder maujood hai, binary/server verify nahi kiya |

**⚠️ IMPORTANT PATH FACT (galti ka khatra):**
- Is agent session ka `$HOME` = `/root` — yeh **Termux home NAHI** hai.
- Asli Termux home = `/data/data/com.termux/files/home`
- `~/android-sdk`, `~/.opencode`, `~/.gitconfig` sab asli Termux home mein hain.
- `/sdcard` aur Termux home **same filesystem** par bind hain (same total blocks) — storage shared hai.

**Git identity (asli Termux global config):** `Project Jessy <project-jessy@localhost>`
→ Naye repo ka local identity set kiya: `NexG IDE <nexg-ide@localhost>` (badal sakte hain).

---

## 3. CONFIRMED USER PREFERENCES (decisions already taken)

1. ❌ **Build abhi nahi karna** — user ne 2 baar mana kiya. Jab tak user na kahe, `./gradlew` mat chalao.
2. ✅ Purana "Project Jessy" project **delete** kar diya gaya (poora folder).
3. ✅ `~/PROJECT` (khali folder) Termux se delete kar diya gaya.
4. ✅ Naya project **"NexG IDE"** naam se `/sdcard/Documents/NexG IDE` mein banaya gaya.

---

## 4. ARCHITECTURE (target — implemented in phases; Phase 1 done)

> ⚠️ **Ye section abhi bhi stale hai.** Ye purane consumer/Gemini-pivot
> architecture ka description hai. Pre-build audit ne ise `PLAN.MD` ke saath
> contradiction bataya tha (audit item A9), aur woh contradiction **aaj bhi hai**.
> Phase 1 implementation `PLAN.MD` Part 4-6 ke mutabiic ki gayi hai, is section
> ke mutabiik nahi. **`PLAN.MD` authoritative hai.** Ye section Phase 2 start
> hone se pehle ya to update hoga ya delete — abhi isko follow mat karo.

```
NexG IDE
├── UI Layer
│   ├── Home / Projects / Workspace / File Explorer
│   ├── Code Editor / Terminal / AI Chat / Voice / Settings
├── Application Layer
│   ├── ProjectManager, FileManager, TerminalManager
│   ├── OpenCodeManager, VoiceManager, CommandRouter
│   └── Permission/Confirmation Manager
├── Integration Layer
│   ├── termux/  ├── ubuntu/  └── opencode/
└── Safety Layer
    ├── command confirmation
    ├── destructive command protection
    ├── permission handling
    └── operation logging
```

**Core principle:** App sirf frontend/orchestrator hai.
Android app ke andar Ubuntu RECREATE nahi karni, fake shell nahi banana hai.
Real Termux/Ubuntu/OpenCode ka backend use karna hai.

---

## 5. HARD RULES (violation = failure)

**Safety**
- Destructive commands (`rm -rf`, `format`, `chmod`, package removal, network config, credential change) kabhi auto-execute nahi → user confirmation mandatory.
- Voice se koi bhi command validation ke bina execute nahi ho.
- Android permission controls bypass nahi karne.

**Security**
- API keys / passwords / tokens: **source code mein kabhi nahi**.
- Secrets log nahi karne, UI logs mein expose nahi karne.
- Agar OpenCode server auth lage → EncryptedSharedPreferences / Keystore use karna.

**Honesty**
- Fake terminal output ❌
- Fake AI response ❌
- Fake OpenCode connection ❌
- `TODO` / `FIXME` / `NotImplementedException` se core feature mark nahi karna.
- External env na hone par: proper interface + real adapter + config + documented dependency + mock-based tests.

**Process**
- Phase khatam = green build. Sirf files banane se "done" nahi kehna.
- Build fail ho toh padho, fix karo, dobara build karo.

---

## 6. TESTING TARGETS (core logic ka unit test chahiye)

- [ ] VoiceStateMachine
- [ ] IntentParser
- [ ] CommandRouter
- [ ] ProjectManager
- [ ] FileManager
- [ ] Destructive command detection
- [ ] Settings persistence
- [ ] OpenCode connection state

---

## 7. WORK LOG

### Entry 001 — 2026-09-29 — Environment inspection + cleanup

**Kya kiya:**
- Termux home, `/sdcard`, Documents, Download inspect kiye.
- Purana project "Project Jessy" locate kiya → `/sdcard/Documents/Project Jessy` (43 MB, 3 `.kt` files, 122 lines = sirf skeleton).
- Backup verify kiya: `gzip -t /sdcard/ProjectJessy-backup.tar.gz` → **OK**.
- User ke confirm ke baad poora folder delete kiya + `~/PROJECT` delete kiya.
- Naya folder banaya: `/sdcard/Documents/NexG IDE`.

**Kya nahi kiya (jaan-boojhkar):**
- Koi build nahi chalaya (user ka instruction).
- Koi naya code nahi likha.

**Verified output:**
```
ls: cannot access '/storage/emulated/0/Documents/Project Jessy': No such file or directory
ls: cannot access '/root/PROJECT': No such file or directory
-rw-rw---- 1 10000 1023 5411646 Sep 27 15:14 /sdcard/ProjectJessy-backup.tar.gz
```

---

### Entry 002 — 2026-09-29 — Git repository setup

**Kya kiya:**
- `.gitignore` banaya (Gradle / Android / IDE / **secrets** patterns).
- `.gitattributes` banaya (LF normalization, binary markers) — sdcard FAT-family hai, isliye zaroori.
- `git init -b main` — branch `main`.
- `core.filemode=false` + `core.symlinks=false` set kiya (sdcard exec bit preserve nahi karta).
- Repo-local identity: `NexG IDE <nexg-ide@localhost>`.
- `safe.directory` add kiya — **dono** paths, kyunki git resolved path use karta hai:
  - `/mnt/sdcard/Documents/NexG IDE` (yeh resolved path hai)
  - `/sdcard/Documents/NexG IDE`
  - Termux ki asli global config `/data/data/com.termux/files/home/.gitconfig` mein bhi add kiya.
- First commit: `22a5593` — 3 files, 282 insertions.

**Trap jo pada (dobara na ho):**
`git config` likhte waqt `fatal: not in a git directory` aaya — yeh asal mein
*ownership check* ki galti thi, config ki nahi. Fix: `safe.directory` mein
**symlink-resolved path** dalo, raw path nahi.

**Verified:**
```
$ git rev-parse --git-dir
.git
$ git log --stat --oneline
22a5593 chore: init NexG IDE repository with project memory
 .gitattributes |  40 +++
 .gitignore     |  60 +++
 OPENCODE.MD    | 182 +++
```

**Kya nahi kiya:** koi build nahi chalaya.

---

### Entry 003 — 2026-09-29 06:45 — Master plan banaya (no code)

**Kya kiya:**
- Environment ka deep reality-check (network, SDK, RAM, proot, opencode, node).
- `docs/PLAN.MD` likha — 534 lines, 8 parts (A–H).

**3 asli problems mile (verified, not assumed):**
1. ❌ **`termux-api` installed nahi** → app Termux ko command nahi bhej sakta.
   `RUN_COMMAND` service missing. Terminal + Ubuntu features ke liye setup chahiye.
2. ⚠️ **Main session proot ke andar hai** (`PD_CONTAINER=ubuntu`, Ubuntu 26.04.1 LTS,
   `/proc/self/root -> .../ubuntu/rootfs`). Isliye yahan se `proot-distro` nahi chal sakta —
   nested proot forbidden. **Expected hai**, app ke liye blocker nahi.
3. 🔴 **RAM sirf 1.0 GiB free** (5.4 GiB total) → Gradle build OOM risk. `jvmargs` tuning mandatory.

**Achha mila:** network 200 (Google Maven/Central/Plugin Portal), SDK `android-36` + build-tools 34/35/36,
`opencode 1.18.33` with **`opencode serve`** (real HTTP API → MODE B possible), node v24.18.0, proot-distro installed.

**Key architectural decision (is problem se nikla):**
> AI ko Termux ki zaroorat nahi — `opencode serve` HTTP deta hai.
> Sirf *terminal* aur *Ubuntu* ko Termux chahiye. Isse app ~90% kaam bina Termux ke karta hai.

**Decisions locked:** manual DI (Hilt nahi — RAM/ksp), Proto DataStore + Room,
SAF for files, OkHttp+SSE for OpenCode, custom Compose `TextFieldValue` editor, no heavy deps.

**Kya nahi kiya:** koi build nahi chalaya, koi source code nahi likha.

---

### Entry 004 — 2026-09-29 — Architecture pivot: consumer product + Gemini backend

**Trigger:** Owner ne product direction badli. Pehle: "personal use, main khud use karunga
(Termux/Ubuntu/OpenCode already set up hai)". Ab: "**users** ka app, user kuch na kare —
bas AI se bole 'ye app banao'". Phir: "**hum gemini API ka system rakhenge**".
Saath me ek formal "NEXG IDE MASTER ARCHITECTURE" spec aayi (Termux+Ubuntu+OpenCode
ke around), jisme bola gaya tha PLAN.MD ko merge karo, replace mat karo.

**Audit (command-based, `git ls-files` + `find -name '*.kt'`):**
```
commits:      4 (sab documentation)
tracked:      .gitattributes, .gitignore, OPENCODE.MD, PLAN.MD, REPORT.MD
source files: 0
git status:   clean
```
→ **Kuch implement nahi hai.** Isliye "purana kaam bachao" = sirf decisions bachao,
code kuch nahi. Aur pivot abhi ZERO cost par ho gaya — 3 din baad nahi hota.

**6 conflicts identify kiye aur PART 2 me explicitly resolve kiye:**

| # | Conflict | Resolution |
|---|---|---|
| C1 | Spec Termux+Ubuntu+OpenCode ko execution layer maanta hai — **Play Store par ship nahi ho sakta** | Architecture **delete nahi**, *adapter* banaya (Tier 2) |
| C2 | Spec OpenCode AI layer maanta hai; owner Gemini bol raha hai | `AiBackend` interface, dono backends |
| C3 | 13 phases me 5 consecutive `termux-api` pe dependent (installed nahi hai) | Reorder — SAF/HTTPS wala pehle |
| C4 | Purana matrix **AGP 8.7 + compileSdk 36** — contradiction (8.7 max 35) | 8.9.2 / 8.13 / Kotlin 2.1.0 |
| C5 | Spec "credentials expose mat karo" bolta hai par **key kaise aayegi** nahi batata | BYOK (user ki apni key) |
| C6 | Build host RAM ceiling (1.6 GiB avail) plan me nahi tha | §7.2 mandatory tuning |

**C1 ka verified evidence:**
```
Termux Play Store se hat chuka hai (2021) — F-Droid/GitHub only
termux-api binary: /data/data/com.termux/files/usr/bin/termux-api → NO SUCH FILE
opencode readelf -d → NEEDED libc.so.6, ld-linux-aarch64.so.1   (glibc, Termux = bionic)
opencode binary size: 177 MB   (Play Store base module limit ~200 MB)
```

**Key architecture decision (C1+C2 dono ka solution):**
> Termux/Ubuntu/OpenCode **"kya hai"** par depend nahi karte — sirf **"kaunsa adapter
> active hai"** par depend karte hain.
> 3 boundaries interface bana diye: `FileSystemPort`, `ExecutorPort`, `AiBackend`.
>
> **Tier 1** (Play Store, users): Sirf SAF + Gemini. Termux ZERO.
> **Tier 2** (sideload, power users): SAF + Termux + Ubuntu + OpenCode.

**Do genuine deductions:**

1. **Gemini sirf code *likh* sakta hai, *chala* nahi sakta.** Isliye Tier 1 me
   "web app banao" perfect hai (WebView me live chalta hai), par "test chala ke fix karo"
   Tier 2 + Gradle chahiye. Ye MVP scope ko honest rakhta hai.

2. **API key APK me kabhi nahi.** Nikaalna 2 minute ka kaam hai + Play Store policy
   violation. BYOK default. Baad me `CloudProxyBackend` aa sakta hai bina UI badle.

**Reordering ka payoff:** Phase 7 ke baad ek **actually usable, Play Store-ready** app
hota hai — voice/text → AI → live preview → diff → approve — **bina Termux ke**.
Termux Phase 8 se *optional upgrade* hai, prerequisite nahi.

**Phase count:** purane 12 → naye 17 (kyunki safety/diff ko alag phase diya, aur
Gemini + preview + multimodal add hue). Par Tier 1 sirf **Phase 1–7** me ship hota hai.

**PLAN.MD overwrite kiya ya nahi:** nahi — Part 0 me **preserve table** hai (13 valid
decisions, mark kiye gaye), Part 2 me **conflict→resolution** ledger, Part 3 me spec ka
diagram *ke saath* interface seam. Purana plan delete nahi hua.

**Verified (naya):**
```
git ls-files          → 4 files (no source)
find -name '*.kt'     → 0
ls .../usr/bin/termux-api → No such file or directory
readelf -d opencode   → NEEDED libc.so.6
du -h opencode        → 177M
free -h               → Mem: 5.4Gi total, 1.6Gi available, Swap 3.8Gi free
curl generativelanguage → HTTP 403 (connectivity OK, sirf key missing)
```

**Kya nahi kiya:** koi build nahi, koi source code nahi, koi dependency install nahi,
owner ke approval ke bina koi implementation change nahi.

---

## 8. OPEN QUESTIONS / BLOCKERS

1. ✅ ~~Package name kya rakhein?~~ → **`com.nexg.ide`** (owner ne Phase 1 approval
   ke saath confirm kiya; manifest aur namespace dono me yahi hai).
2. ✅ ~~`ANDROID_HOME` kahan set karna hai?~~ → `local.properties` mein `sdk.dir` dena hai
   (resolved: `/data/data/com.termux/files/home/android-sdk`). Platforms `android-34`, `android-36` available.
3. ❓ proot-distro actually installed hai ya nahi (verify nahi hua)?
   → **Partially resolved (Entry 005):** `proot` aur `proot-distro` binaries installed
   hain aur kernel `6.17.0-PRoot-Distro` hai. Lekin app on-device run nahi hui
   (`adb devices` khaali), to runtime behaviour verified nahi. Details + W^X risk
   Entry 005 me. **Phase 8 me on-device verify karna hai.**
4. ❓ OpenCode server mode use hoga ya terminal-launch mode? (spec server/API prefer karta hai)
5. ✅ ~~Android SDK versions available hain ya nahi?~~ → **HAAN**: platforms `android-34`, `android-36`;
   build-tools `34.0.0`, `35.0.0`, `36.0.0`. `compileSdk 36` / `targetSdk 36` use kar sakte hain.
6. ❓ Git identity theek hai? (`NexG IDE <nexg-ide@localhost>` — placeholder jaisa lag raha hai)
7. ✅ ~~`./gradlew` chalega ya nahi?~~ → **NHI, is repo directory me nahi chalega.**
   `/storage/emulated` par `noexec` hai. `sh gradlew <task>` use karo. Entry 005 me
   poora evidence hai.
8. ❓ `NexG_IDE_PLAN.MD` (PLAN.MD ka byte-identical duplicate) kya karein?
   Owner se confirm karna hai — merge ya delete. Abhi commit se bahar hai.

---

## 9. PHASE STATUS

| Phase | Naam | Tier | Status |
|---|---|---|---|
| 0 | Repository audit + plan merge | — | ✅ DONE |
| 1 | App shell, theme, nav, core/ | 1 | ✅ DONE (verified: debug APK + 39 tests) |
| 2 | SAF + ProjectManager + Room + Explorer | 1 | ⬜ |
| 3 | Code editor (7 languages) | 1 | ⬜ |
| 4 | Ports + **GeminiBackend** + BYOK | 1 | ⬜ |
| 5 | AI chat + streaming UI | 1 | ⬜ |
| 6 | **Code gen → WebView preview** | 1 | ⬜ |
| 7 | **Safety + Diff + approval** | 1 | ⬜ |
| 8 | Termux detect + ExecutorPort | 2 | ⬜ ⚠️ needs `termux-api` |
| 9 | Terminal UI | 2 | ⬜ ⚠️ |
| 10 | Build engine (Gradle) | 2 | ⬜ ⚠️ |
| 11 | Logs / Logcat | 2 | ⬜ ⚠️ |
| 12 | OpenCodeBackend + Ubuntu | 2 | ⬜ ⚠️ |
| 13 | Git | 2 | ⬜ ⚠️ |
| 14 | Voice (state machine, 17 intents) | 1 | ⬜ |
| 15 | Multimodal (screenshot → fix) | 1 | ⬜ |
| 16 | Hardening + accessibility + error-to-fix | — | ⬜ |
| 17 | Release build (Play Store + sideload) | — | ⬜ |

> **Milestone:** Phase 7 ke baad — usable, Play Store-ready app, **zero Termux dependency**.

---

### Entry 005 — 2026-09-29 — Phase 1 IMPLEMENTED + verified (first source code)

Owner ne Phase 1 build start karne ki approval di. Ab repo me 27 Kotlin files hain,
debug APK ban raha hai, 39 unit tests pass hain.

#### Build environment: 4 real problems, 4 verified fixes

Ye teenon commands nahi chalenge aise. `sh gradlew` use karna zaroori hai.

| # | Problem | Evidence | Fix |
|---|---|---|---|
| 1 | **`./gradlew` impossible** — sdcard exec bit support nahi karta | `chmod +x gradlew` → `-rw-rw----` rehta hai; `./exectest.sh` → `Permission denied`, `exit=126`; `/proc/mounts`: `/storage/emulated fuse ... **noexec**`; `git config core.filemode` = `false` | Har command `sh gradlew <task>` se chalana. Git index me mode `100755` set kiya gaya hai taaki doosre machine par normal checkout executable ho |
| 2 | **AGP ka aapt2 is device par start nahi hota** | `aapt2-8.9.2-12782657-linux.jar` ka binary: `Machine: Advanced Micro Devices X86-64`, `NEEDED libc.so.6`, `NEEDED ld-linux-x86-64.so.2`. Device: `aarch64` + bionic. Build fail: `AAPT2 ... Daemon #0: Daemon startup failed` | `android.aapt2FromMavenOverride` → SDK ka aapt2. Device par SDK ka wala `Machine: AArch64`, `NEEDED libc.so` (bionic), aur `aapt2 version` = `2.20-android-16.0.0_r4` |
| 3 | `aapt2FromMavenOverride` repo me nahi daala | Ye machine-specific hai, har developer ke alag path hoga | `$GRADLE_USER_HOME/gradle.properties` me rakha (git se bahar). Repo ki `gradle.properties` bilkul as-mandated, unmodified hai |
| 4 | Metaspace warning → ek **transient** `packageDebug` fail | `Daemon will be stopped at the end of the build after running out of JVM Metaspace` | `-XX:MaxMetaspaceSize=384m` as-mandated rakha. Retry pass hua, aur **clean build + test dono pass** — reproducible hai. RAM floor build ke dauraan: **947 MB available** |

#### Build matrix — as specified, no substitution

```
Gradle wrapper   8.13   (verified: gradle-8.13-bin.zip)
AGP              8.9.2
Kotlin           2.1.0
Compose plugin   2.1.0   (org.jetbrains.kotlin.plugin.compose)
Compose BOM      2025.01.00
compileSdk/targetSdk 36   minSdk 26   JVM target 17   (JDK 21.0.12 installed)
sdk.dir          /data/data/com.termux/files/home/android-sdk
```

Saanche AndroidX versions pehle HTTP 200 se verify kiye: core-ktx 1.15.0,
lifecycle 2.8.7, activity-compose 1.9.3, navigation-compose 2.8.5.

#### Verification evidence

```
$ sh gradlew :app:assembleDebug            BUILD SUCCESSFUL   (clean state se)
$ sh gradlew :app:testDebugUnitTest        BUILD SUCCESSFUL
$ sh gradlew :app:testDebugUnitTest --rerun-tasks   BUILD SUCCESSFUL
```

39 tests, 0 failures, 0 errors, 0 skipped:

| Test class | Tests |
|---|---|
| `core.log.SecretRedactorTest` | 11 |
| `core.log.AppLoggerTest` | 5 |
| `core.result.AppResultTest` | 11 |
| `ui.theme.ColorContrastTest` | 12 |

Artifact: `app/build/outputs/apk/debug/app-debug.apk` — 16 MB,
`aapt2 dump badging` se confirm: `package=com.nexg.ide`, `minSdkVersion=26`,
`targetSdkVersion=36`.

**Release pipeline bhi verify hui** (Phase 1 requirement nahi, par proot check ke
liye useful): `:app:assembleRelease` SUCCESSFUL incl. `lintVitalRelease`, aur
`apksigner sign` + `verify --print-certs` native tool bhi chala — matlab
aapt2 → R8/d8 → zipalign → apksigner ka poora chain is device par kaam karta hai.

#### Do asli bugs jo tests ne pakde

1. **Security leak (logger).** `Authorization: Bearer <token>` pe named-assignment
   rule `Bearer` ko value samajh kar `Authorization:[REDACTED] <token>` bana raha
   tha — yani **actual token log me leak**. Fix: BEARER rule named-assignment se
   pehle chalta hai, aur value == "bearer" ho to named-assignment haath nahi
   karta. Ye bug bina test ke ship hota.
2. **Galat accessibility numbers.** `Color.kt` me hand-estimated contrast ratios
   the (cyan "9.4", mint "9.9"). Asli: cyan **13.17**, mint **15.17**, amber
   **11.07**, red **6.30**, text-on-black **17.12**. Test ne turant fail kiya.
   Ab `CONTRAST_NOTES` measured hai aur `ColorContrastTest` un numbers ko
   assert karta hai — colour badaloge to test fail hoga, comment chupke se
   stale nahi hoga.

#### Contrast — actually measured, not claimed

`ColorContrastTest` WCAG 2.x relative-luminance formula chala kar ratios verify
karta hai. Dark: text 17.12:1 (AAA), secondary 7.99:1 (AAA), violet-on-black
4.66:1 (AA, kam headroom — body text ke liye NAHI use karna). Light:
18.29:1 (AAA), aur light-mode ke darkened accents 4.5:1 cross karte hain
(asli violet/cyan white pe FAIL hote hain — isi liye light scheme alag hai).

#### Kya jaan-boojh kar Phase 1 se bahar rakha

- Room/KSP/DataStore/OkHttp/serialization/security-crypto **add nahi kiye** —
  wo Phase 2+ (data), 4 (network), 14 (voice) ke hain. Sirf wahi dependencies
  jo Phase 1 compile karta hai. Isse build halka bhi raha.
- `domain/`, `data/`, `integration/`, `security/`, `terminal/`, `voice/`
  directories **khaali banaye nahi** — git empty dirs track nahi karta, aur
  aage se khali folders sirf "structure ban gaya" ka illusion banate hain.
  Inki Phase ownership: 2/4/8/12/14.
- `Build` route ka optional `projectId` argument **nahi rakha**. Phase 1 koi
  value supply nahi karta, aur route string abhi simple rakhna behtar hai —
  Phase 10 me argument add karna ek line ka kaam hai.
- `record_audio` permission manifest me **nahi** — Phase 14 ka hai.
- `INTERNET` permission declare hai (BYOK/Gemini kaam Phase 4 me hai), par
  Phase 1 me **koi network call nahi**.
- Gemini key, koi credential, koi fake Termux shell — kuch nahi.

#### proot release check — ❌ UNVERIFIED (PASS nahi hai)

**Ye honestly verify nahi hua, aur ise pass nahi mark kiya gaya.**

Jo pata chala:
- Kernel `6.17.0-PRoot-Distro` hai — yaani ye device proot ke liye patched
  kernel use karta hai (stock Android kernel nahi). Ye proot ke favour mein hai.
- `/data` par `noexec` flag nahi hai; `/storage/emulated` par `noexec` hai.
- `proot` aur `proot-distro` binaries installed hain.

Jo verify nahi hua, aur kyun:
- **App is device par install/run nahi hui.** `adb` binary maujood hai, daemon
  chalu hua, lekin `adb devices` khaali hai; `localhost:5555` / `127.0.0.1:5555`
  par `Connection refused`. Wireless debugging ka pairing code chahiye jo
  bina owner ke interaction ke nahi mil sakta. Isi liye app runtime par
  ptrace/SELinux behaviour observe nahi kiya ja sakta.
- Phase 1 me proot integration code **hi nahi** hai, to test karne ko kuch
  nahi tha.

Open risk (Phase 8 me resolve karna hai, evidence ke saath): `targetSdk 36`
ke against Android ka **W^X rule** — app-writable storage se binary exec
block hota hai. Termux isliye `targetSdk 28` par hai. NexG IDE `36` par hai,
isliye proot guest binaries ko app private dir me extract karke chalana
allow nahi hoga; bind-mount ya W^X-safe rakhne ka alternative chahiye.
**Yeh assumption nahi hai, ye known Android restriction hai — Phase 8 me
on-device verify karna mandatory hai.**

**Next verification point:** Phase 8 (Termux + ExecutorPort), jab actual proot
spawn code hoga tab on-device test, aur wahan bhi wahi 4 build-environment
constraints upar wale hi rahenge.

#### Stray file — flag, delete nahi kiya

`NexG_IDE_PLAN.MD` (610 lines) repo root par pada hai aur `PLAN.MD` ka
**byte-identical duplicate** hai (dono md5 `fe81cadc0b99efda59ef210241db4a67`).
Maine nahi banaya, isliye delete nahi kiya aur commit se bahar rakha hai.
"Do files me se ek single source of truth" khud plan ka rule hai, isliye owner
se confirm karna hai — merge kar dein ya hata dein.

---

**Last updated:** 2026-09-29 — Entry 005 (Phase 1 implemented + verified)
**Files ab root par hain:** `PLAN.MD` (single source of truth) · `OPENCODE.MD` · `REPORT.MD` · `AUDIT-REPORT.txt`
**Git:** docs commits + Phase 1 implementation commit, branch `main`.
**Next action:** Phase 2 (SAF + ProjectManager + Room + Explorer) — owner ke green light se.
**Approval rule:** Owner ke green light ke bina koi implementation start nahi hoga.

---

