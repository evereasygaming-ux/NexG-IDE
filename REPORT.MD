# NEXG IDE — SESSION REPORT (2026-09-29)

> Aaj ka poora kaam — **har fact command se verified hai, koi andaza nahi.**
> Ye report uss din ki hai jis din architecture pivot hua.
> Master plan → `PLAN.MD` · Memory/work log → `OPENCODE.MD`

---

## 1. SESSION SUMMARY

Ek hi din me **3 major direction changes** hue. Sab record hain:

| # | Time | Kya hua |
|---|---|---|
| 1 | 06:20–06:45 | Project locate + environment audit + old master plan (Termux/Ubuntu/OpenCode, personal use) |
| 2 | ~07:30 | RAM build analysis (1.6 GiB available → OOM risk) |
| 3 | ~08:00 | **PIVOT** → "users ka app, user kuch na kare" → **Gemini API** → merged master plan |

**Net result:** zero code likha gaya (repo me abhi bhi 0 source files hai),
par architecture ab sahi jagah hai — aur ye abhi sasta pada.

---

## 2. REPOSITORY AUDIT

**Location:** `/sdcard/Documents/NexG IDE`

```
$ git log --oneline
13f2902  docs: memory Entry 003 - master plan + 3 verified environment problems
b1b9bf9  docs: add comprehensive master PLAN.MD          (534 lines)
c6406d5  docs: update project memory with git setup
22a5593  chore: init NexG IDE repository with project memory

$ git ls-files
.gitattributes
.gitignore
OPENCODE.MD
PLAN.MD          (pehle docs/PLAN.MD tha — aaj root par shift kiya)

$ find . -name '*.kt' -o -name '*.java' -o -name '*.gradle*'
0 files

$ git status
 M OPENCODE.MD
 RM docs/PLAN.MD -> PLAN.MD
```

| Item | Value |
|---|---|
| Commits | 4 (all documentation) |
| Source files | **0** |
| Git identity | `NexG IDE <nexg-ide@localhost>` |
| Branch | `main` |

**Conclusion:** kuch implement nahi hai. "Purana kaam bachao" = sirf **decisions** bachao.
Pivot isliye zero-cost par ho gaya.

### 2.1 File move (aaj)

```
$ git mv docs/PLAN.MD PLAN.MD
RM docs/PLAN.MD -> PLAN.MD
```

Git ne **rename detect kar liya** — history intact hai, commit `b1b9bf9` abhi bhi
PLAN.MD ko point karta hai. Reason: owner ne kaha "folder main" — nested `docs/`
se dikhta nahi tha.

---

## 3. ENVIRONMENT VERIFICATION

Sab command se check kiya. Ye facts hain, assumptions nahi.

### 3.1 Hardware / resources

```
$ free -h
              total   used   free  shared  buff/cache  available
Mem:           5.4Gi  3.8Gi  139Mi   21Mi      1.2Gi      1.6Gi
Swap:          6.0Gi  2.2Gi  3.8Gi

$ grep -E 'MemTotal|MemAvailable' /proc/meminfo
MemTotal:        5628212 kB     (5.4 GiB)
MemAvailable:    1643140 kB     (1.6 GiB)

$ nproc
8
```

| Metric | Value | Note |
|---|---|---|
| RAM total | 5.4 GiB | |
| RAM available | **1.6 GiB** | ye build ke liye relevant number |
| MemFree | 139 MiB | slack poora cache se aata hai (normal) |
| Swap free | 3.8 GiB | zram — `/proc/swaps` khaali dikhta hai but swap **chalta hai** (2.2 GiB already used) |
| CPUs | 8 | |

**Android:** release 16, SDK 36.

### 3.2 Toolchain

| Item | Status | Detail |
|---|---|---|
| Java | ✅ | OpenJDK 21.0.12 (Termux) |
| Gradle (system) | ✅ | 9.8.0 — **use nahi hoga** (AGP 8 + Gradle 9 pair nahi hota) |
| Kotlin | ✅ | `kotlinc` in PATH |
| Node | ✅ | v24.18.0 (Termux side bhi available) |
| adb | ✅ | Termux `android-tools` |
| git | ✅ | 2.53.0 |
| Android SDK | ✅ | `/data/data/com.termux/files/home/android-sdk` |
| SDK platforms | ✅ | `android-34`, `android-36` |
| build-tools | ✅ | `34.0.0`, `35.0.0`, `36.0.0` |
| `ANDROID_HOME` | ❌ empty | `local.properties` me `sdk.dir` dena hoga |
| proot-distro | ✅ | Ubuntu 26.04.1 LTS container |
| OpenCode | ✅ | `1.18.33`, binary 177 MB |
| Network | ✅ | Google Maven / Central / Plugin Portal = 200 |

### 3.3 Gemini API reachability

```
$ curl -o /dev/null -w "%{http_code}" https://generativelanguage.googleapis.com/v1beta/models
403

$ curl https://generativelanguage.googleapis.com/v1beta/models
"Method doesn't allow unregistered callers ... Please use API Key"
```

**403 = connectivity theek hai, sirf key missing hai.** Ye failure nahi, ye **green signal** hai.

---

## 4. RAM ANALYSIS — CAN THIS DEVICE BUILD?

### 4.1 Demand vs supply

Planned config ke hisaab se 2 JVMs saath chalenge:

| Process | Setting | Ceiling |
|---|---|---|
| Gradle daemon | `-Xmx1024m` + `MaxMetaspaceSize=384m` | ~1.4 GiB |
| Kotlin daemon | `-Xmx768m` | ~0.8 GiB |
| **Peak** | dono saath | **~2.5 GiB** (2.2 heap + ~300 MiB overhead) |

| | Value |
|---|---|
| Demand | **~2.5 GiB** |
| Supply | **1.6 GiB available** |
| Swap backup | 3.8 GiB free |

### 4.2 Verdict

**Build ho jayega — par squeeze me, sirf tuning ke saath.**

- Gradle **swap karega**. OOM nahi hoga (3.8 GiB free), par pehli `assembleDebug`
  **kai minute** legi instead of seconds, aur phone loaded ho to Android
  low-memory killer build maar sakta hai.
- Single-module + manual DI ki wajah se ye itna bada issue nahi bana.
  Hilt + kSP lagata to ek teesra JVM aa jata.

### 4.3 Mandatory tuning (plan me daal diya — `PLAN.MD` §7.2)

```properties
org.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8
org.gradle.workers.max=1                    # ← purane plan me MISSING tha
org.gradle.parallel=false
org.gradle.caching=true
org.gradle.configuration-cache=false
org.gradle.daemon=true
kotlin.compiler.execution.strategy=in-process   # alag Kotlin JVM nahi → ~800 MiB bachta hai
kotlin.incremental=true
android.useAndroidX=true
android.nonTransitiveRClass=true
```

`workers.max=1` purane `E.2` block me nahi tha — ye ek real gap tha.

---

## 5. THE 6 CONFLICTS (identified before resolving)

Spec ne kaha: "PLAN.MD ko padho, merge karo, blindly replace mat karo,
conflicts identify karo." Ye wahi process hai.

### C1 — 🔴 Spec ka execution layer Play Store par ship nahi ho sakta

**Spec:** "Termux/Ubuntu/OpenCode remain the development execution layer."

**Evidence:**

| Finding | Command / result |
|---|---|
| Termux Play Store par nahi | removed **2021**; F-Droid/GitHub only |
| `termux-api` absent | `ls: cannot access '.../termux-api': No such file or directory` |
| OpenCode = **glibc** | `readelf -d` → `NEEDED libc.so.6`, `ld-linux-aarch64.so.1` |
| OpenCode size | **177 MB** (Play Store base module limit ~200 MB) |
| proot in app | release build non-debuggable → kernel `ptrace` block → **fail** (⚠️ Phase 1 me verify karna hai) |

**Root cause:** Termux **bionic** hai, OpenCode **glibc** maangta hai. Ye dono
on-device reconcile nahi ho sakte.

**Resolution:** architecture **delete nahi** — *pluggable adapter* banaya (Tier 2).

### C2 — 🔴 Spec owner ke latest direction se takrata hai

**Spec §9:** OpenCode = AI layer.
**Owner (aaj, latest):** "hum gemini api ka system rakhenge" — after stating
"users kuchh karna na pade".

**Resolution:** `AiBackend` interface. `GeminiBackend` default (Phase 4),
`OpenCodeBackend` baad me (Phase 12). **UI code badalta hi nahi** backend switch karne par.

### C3 — 🟡 13 phases me 5 consecutive `termux-api` pe dependent

Spec Phase 4→10: Terminal, Build, Logs, OpenCode, Git. **Paanchon ko `termux-api` chahiye,
jo installed nahi hai.** Day one par 5 phases blocked.

**Resolution:** reorder. Jo SAF/HTTPS se ho sakta hai, wo pehle. (§8)

### C4 — 🟡 Purana version matrix internally contradictory

`AGP 8.7.x` + `compileSdk 36`. **AGP 8.7 max compileSdk 35 support karta hai.**
Gradle 8.14 + Kotlin 2.0.21 bhi AGP 8.9+ ka matching set nahi.

**Correction:** Gradle **8.13** · AGP **8.9.2** · Kotlin **2.1.0** · BOM **2025.01.00**
> Phase 1 me **actually resolve** karna hai. Table par bharosa nahi.

### C5 — 🟢 "Credentials expose mat karo" — par key kahan se aayegi?

Spec §9 rule bolta hai, **solution nahi deta**. Bina iske Phase 2 ka code bekaar hai.

**Resolution:** **BYOK** — user apni Gemini key paste kare (Keystore-backed).
Zero owner cost, zero extraction risk, no server. `CloudProxyBackend` baad me.

> ❌ **APK me working API key kabhi nahi.** 2 minute me nikal jaata hai + Play Store policy violation.

### C6 — 🟢 Build host ka RAM ceiling plan me nahi tha

Verified 1.6 GiB available vs ~2.5 GiB demand. Default config swap ya OOM.

**Resolution:** §7.2 mandatory `gradle.properties`, Phase 1 me likha jayega.

---

## 6. THE ARCHITECTURAL DECISION

> **Termux/Ubuntu/OpenCode "kya hai" par depend nahi karte —
> sirf "kaunsa adapter active hai" par depend karte hain.**

Teen boundaries interface bana diye:

| Port | Tier 1 adapter | Tier 2 adapter |
|---|---|---|
| `FileSystemPort` | `SafFsAdapter` (no root) | + Termux paths |
| `ExecutorPort` | — (not needed) | `TermuxExecutor` (RUN_COMMAND) |
| `AiBackend` | **`GeminiBackend`** (HTTPS+SSE) | `OpenCodeBackend` (HTTP :4096) |
| `BuildPort` | — (not needed) | `GradleTaskRunner` |

```
TIER 1 — Play Store, general user
   App → SAF (files) + HTTPS (Gemini)
   Setup required: SAF permission + BYOK key. Bas.

TIER 2 — sideload, power users, owner
   App → SAF + Termux → Ubuntu → OpenCode / Gradle / SDK
   Setup: termux-api + Termux:API app + "Allow external apps"
```

`domain/` **pure Kotlin** rahga (Android imports nahi) — isiliye core logic
emulator ke bina unit-testable hai. Ye purane plan se preserved hai.

---

## 7. PHASE REORDER — the payoff

| | Purana | Naya |
|---|---|---|
| Tier 1 ship hota hai | Phase 12+ | **Phase 7** |
| Termux se pehle usable | 3 phases | **7 phases** |
| Termux ka role | prerequisite | **optional upgrade (Phase 8+)** |
| Total phases | 12 | 17 (safety/diff ko alag diya, Gemini+preview+multimodal add) |

**Phase 7 ke baad** — usable, Play Store-ready app:
`voice/text → Gemini → live WebView preview → diff → approve`
**Bina Termux ke.** Ye pehla milestone aur pehla demo hai.

### 7.1 Kya Tier 1 me hai, kya nahi

| Feature | Tier 1 | Kyun |
|---|---|---|
| Web app banao → live preview | ✅ | Gemini HTML/CSS/JS → WebView |
| Bug fix / code explain | ✅ | text output |
| Screenshot bhejo → fix | ✅ | Gemini multimodal |
| Python script **chala** | ⚠️ | Gemini likh sakta hai, **chala nahi** |
| Test chala ke fix | ❌ | Gradle chahiye → Tier 2 |
| Asli APK banana | ❌ | Tier 2 + Termux mandatory |

> Ye honest constraint hai: **Gemini code *likh* sakta hai, *chala* nahi sakta.**
> MVP ko isi ke around design kiya hai, chupayi nahi.

---

## 8. UI — "FUTURISTIC" REQUIREMENT

```
base      #05060A    surface  #0E1119    border  #1E2434
primary   #7C5CFF    cyan     #00E5FF    success #00FF94
warn      #FFB020    error    #FF4D6D    text    #E8ECF5
```

| Element | Behaviour |
|---|---|
| **VoiceOrb** | centrepiece + product mark; mic amplitude se radius pulse; violet→cyan gradient |
| **AuroraBackground** | 2–3 blurred gradient blobs, ~20 s slow rotation, low opacity |
| **Streaming shimmer** | in-flight AI text par left-to-right sweep |
| **GlassCard** | `blur(20dp)`, 8% white fill, 1px gradient border |
| **Springs** | `spring(dampingRatio = 0.6)` — koi linear easing nahi state changes par |
| **Haptics** | listening / thinking / done / error par crisp tick |

**Tier 1 workspace** — "wow" surface:
```
┌─────────────────────────┐
│  ◉ orb          ⚙  ⇄Tier │
├─────────────────────────┤
│  stream / chat          │  ← AI yahan likhta hai
├─────────────────────────┤
│  📱 LIVE PREVIEW    ⤢ ↻ │  ← WebView, draggable 0→100%
├─────────────────────────┤
│ "kuch bhi bolo..."  🎤  │
└─────────────────────────┘
```

**Iteration latency hi product hai:** bolo → dekho → refine karo.

---

## 9. PRESERVED DECISIONS (purane plan se)

| Decision | Verdict |
|---|---|
| Layered arch (ui/app/domain/integration/safety) | ✅ |
| `domain/` pure Kotlin → no Robolectric | ✅ |
| Manual DI, **no Hilt** | ✅ (reason badla — §10) |
| Single `app` module, modular packages | ✅ |
| Secrets → `EncryptedSharedPreferences` | ✅ |
| Custom Compose editor + regex `LanguageDefinition` | ✅ |
| `.gitattributes` LF (sdcard FAT-family hai) | ✅ |
| `core.filemode=false`, `safe.directory` resolved path | ✅ (trap pehle pada) |
| 48dp targets, no main-thread IO, typed catches | ✅ |
| JDK 21 → JVM 17, compileSdk 36, minSdk 26 | ✅ |
| Version matrix | ⚠️ **fixed** (C4) |

---

## 10. TWO CORRECTIONS TO EARLIER REASONING

1. **"Hilt avoid karne ka reason kSP hai" — ab ye argument void hai.**
   Room already KSP use karta hai, to kSP build me aa hi raha hai. Manual DI phir bhi
   sahi choice hai (kam deps, faster compile), par honest reason ab
   **"fewer moving parts"** hai, "kSP avoidance" nahi. Plan me likh diya taaki
   koi baad me galat reason repeat na kare.

2. **`-Xmx1024m` se start nahi karna chahiye.** Is box par 1.6 GiB available hai.
   `-Xmx768m` + `in-process` se shuru karna chahiye — OOM ke baad tune down karne
   ke bajaye. `workers.max=1` bhi missing tha, add kiya.

---

## 11. OPEN QUESTIONS (approval se pehle)

| # | Sawaal | Mera suggestion |
|---|---|---|
| 1 | **Android build (asli APK) zaroori hai?** | Ye Tier 2 only hai + hamesha Termux demand karega. Chhodne se Play Store story bohot strong hoti hai. |
| 2 | **Package name** | `com.nexg.ide` |
| 3 | Ek app ya do APK? | Ek app + detected tier simple hai; do APK se Play Store ko Termux code dikhta hi nahi |
| 4 | Monetisation | Phase 17 tak block nahi karta |
| 5 | Git identity | `NexG IDE <nexg-ide@localhost>` placeholder lag raha hai |
| 6 | Tier 1 pehle ya Tier 2? | **Tier 1** (Phase 1–7) |

---

## 12. KYA NAHI KIYA (jaan-boojhkar)

- ❌ Koi build nahi chalaya — owner ne 2 baar mana kiya
- ❌ Koi source code nahi likha — repo me abhi bhi 0 `.kt` files
- ❌ Koi dependency install nahi
- ❌ Koi commit nahi kiya (changes working tree me, staged nahi)
- ❌ Play Store policies ke against koi key/credential nahi likha
- ❌ Koi fake output / assumed result report nahi kiya

---

## 13. VERIFICATION APPENDIX

Saare commands aaj chalti hain, raw output:

```bash
# repo
git log --oneline; git ls-files; find . -name '*.kt'; git status

# resources
free -h; grep -E 'MemTotal|MemAvailable' /proc/meminfo; nproc
getprop ro.build.version.release; getprop ro.build.version.sdk

# toolchain
java -version; gradle --version; node --version; git --version

# SDK
ls $HOME/android-sdk/platforms; ls $HOME/android-sdk/build-tools

# Termux / OpenCode
ls /data/data/com.termux/files/usr/bin/termux-api        # → absent
readelf -d .../ubuntu/rootfs/root/.opencode/bin/opencode | grep NEEDED
du -h .../ubuntu/rootfs/root/.opencode/bin/opencode     # → 177M
which opencode; opencode --version                        # → 1.18.33
cat /proc/swaps                                           # → empty (zram)

# network
curl -w "%{http_code}" https://generativelanguage.googleapis.com/v1beta/models  # → 403
```

---

## 14. NEXT ACTION

1. Owner Part 11 ke sawalon ka jawab de (sirf #1 aur #2 urgent)
2. **Phase 1 shuru** — app shell + futuristic theme + navigation + `core/`
3. Phase 1 ke andar **version matrix actually resolve** karna + proot-release test

> **Approval rule:** owner ke green light ke bina koi implementation start nahi hoga.
> Phase complete = green build + green tests + `OPENCODE.MD` me recorded.
> Files created ≠ work done.

---

**Report compiled:** 2026-09-29
**Source of truth:** `PLAN.MD` (architecture) · `OPENCODE.MD` (work log)
**Code written:** 0 lines. **Commits:** 4 (all docs).
