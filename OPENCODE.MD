# OPENCODE.MD — NexG IDE Project Memory

> Yeh file project ki permanent memory hai. Har kaam (ya har phase ke end) par update hoti hai.
> Isko kabhi delete mat karna. Yeh source of truth hai.

---

## 1. IDENTITY

| Field | Value |
|---|---|
| **Project name** | NexG IDE |
| **Type** | Personal Android AI-assisted Voice IDE |
| **Owner** | Personal use only (no Play Store publish) |
| **Location** | `/sdcard/Documents/NexG IDE` |
| **Status** | NOT STARTED — folder khali hai |
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

## 4. ARCHITECTURE (target — abhi implement nahi hui)

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

## 8. OPEN QUESTIONS / BLOCKERS

1. ❓ Package name kya rakhein? (`com.nexg.ide`? `com.project.nexg`?)
2. ✅ ~~`ANDROID_HOME` kahan set karna hai?~~ → `local.properties` mein `sdk.dir` dena hai
   (resolved: `/data/data/com.termux/files/home/android-sdk`). Platforms `android-34`, `android-36` available.
3. ❓ proot-distro actually installed hai ya nahi (verify nahi hua)?
4. ❓ OpenCode server mode use hoga ya terminal-launch mode? (spec server/API prefer karta hai)
5. ✅ ~~Android SDK versions available hain ya nahi?~~ → **HAAN**: platforms `android-34`, `android-36`;
   build-tools `34.0.0`, `35.0.0`, `36.0.0`. `compileSdk 36` / `targetSdk 36` use kar sakte hain.
6. ❓ Git identity theek hai? (`NexG IDE <nexg-ide@localhost>` — placeholder jaisa lag raha hai)

---

## 9. PHASE STATUS

| Phase | Naam | Status |
|---|---|---|
| 0 | Repository inspect | ✅ DONE |
| 1 | Android foundation (buildable app, Compose, nav, theme) | ⬜ NOT STARTED |
| 2 | Project/File manager | ⬜ NOT STARTED |
| 3 | Code editor | ⬜ NOT STARTED |
| 4 | Terminal architecture | ⬜ NOT STARTED |
| 5 | Termux integration | ⬜ NOT STARTED |
| 6 | Ubuntu integration | ⬜ NOT STARTED |
| 7 | OpenCode integration | ⬜ NOT STARTED |
| 8 | AI workspace | ⬜ NOT STARTED |
| 9 | Voice assistant | ⬜ NOT STARTED |
| 10 | Security/confirmation system | ⬜ NOT STARTED |
| 11 | Persistence/settings | ⬜ NOT STARTED |
| 12 | Testing + polish + debug APK | ⬜ NOT STARTED |

---

**Last updated:** 2026-09-29 06:32
**Git:** repo initialised, branch `main`, pehla commit `22a5593`.
**Next action:** User se Phase 1 start karne ki permission + package name confirm karna.
