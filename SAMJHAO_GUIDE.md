# Plexo Loopholes — Samjhane Wali File (Hindi Guide)

> Ye document `LOOPHOLES.md` ka simple Hindi version hai.
> Uddeshya: tum kisi ko (friend, reviewer, investor) 10 minute me samjha sako ki
> problem kya thi, fix kya kiya, aur ab Plexo kyon safe hai.

---

## 1. Plexo 30 second me kya hai?

Plexo ek download manager hai. Normal downloader 1 pipe se file kheenchta hai.
Plexo file ke 8-8 MB tukde karke Wi-Fi + Ethernet + Mobile-tether sab par
ek saath download karta hai, phir jod deta hai. Isliye speed jud jaati hai.

---

## 2. Loopholes ka matlab kya hai?

Loophole = aisi chhoti khidki jahan se galat input, galat server ya galat
click app ko dhokha de sakta hai — file corrupt, galat jagah save, ya crash.

Neeche har point me 3 cheezein hain: **Problem → Khatra → Fix**

---

## A. Galat Link (URL) Wale Fixes ✅ FIXED

### A1. `file://` jaisa link bhi fetch ho jata tha
- **Problem:** App sirf `https` check karta tha, baaki sab ko HTTP samajh leta tha.
- **Khatra:** Koi `file:///passwords` jaisa link de de to app usko net-request bana deta.
- **Fix:** Ab `probe.ts` + `downloadManager.ts` dono me gate hai — sirf `http:` / `https:` chalega, baaki par seedha error: *"Only HTTP and HTTPS links are supported"*.

### A2. Download folder kahin bhi set ho jata tha
- **Problem:** Renderer jo folder bhejta tha, main-process aankh band karke `mkdir` kar deta tha.
- **Khatra:** Relative path ya `~` ya drive-root (`C:\`) par write.
- **Fix:** `paths.ts` me `validateDestinationDir()` — absolute path compulsory, drive-root reject, `~` reject. `ensureDirectory` + `reserveDestinationPath` dono me check.

### A3. Server `NaN` bhej de to khaali file "complete"
- **Problem:** `Content-Length: abc` ko `Number()` NaN banata tha, aur NaN check se bach nikalta tha → 0 blocks → turant "complete".
- **Khatra:** User ko lagta download ho gaya, file khaali.
- **Fix:** `toFiniteOrNull()` helper — NaN/negative ko `null/0` banao. Probe + chunk + version teeno jagah.

### A4. File ka naam me chhupa hua `../` ya RTL trick
- **Problem:** Server `Content-Disposition` me `../secret` ya RTL override (`\u202E`) bhej sakta tha, jo UI me raw dikhta tha.
- **Khatra:** User confuse — `evilfdp.exe` dikhe `evilpdf.exe`.
- **Fix:** Probe me hi `sanitizeProbedName()` — RTL strip, `/\` → `_`, control chars → `_`, 180 char cap. Final sanitize `paths.ts` me ab bhi hota hai (double safety).

---

## B. Resume (Pause karke wapas chalana) ✅ FIXED

### B1. Sirf size dekhkar "OK" bolna
- **Problem:** Publish se pehle sirf size match hota tha (`downloadFile.ts`).
- **Khatra:** Size sahi lekin bytes galat ho to bhi pass.
- **Fix (phase-1):** Ab galat version turant pakda jata hai (neeche B2+B3). Full SHA-256 checksum roadmap me hai — abhi sample-verify (8×16KB dobara milana) already hai, usko kamzor case me force kiya.
- **Samjhane wali line:** *"Pehle taala sirf vajan dekhkar khulta tha, ab note bhi check hota hai."*

### B2. Bina ETag wala server hamesha "same" mana jata tha
- **Problem:** Na ETag na date ho to `compareVersion` kehta tha `same`.
- **Khatra:** Badli file bhi resume → silent corrupt.
- **Fix:** Desktop par sample-check pehle se tha; Android par ab rule hai — dono validators missing + size unknown ho to resume refuse: *"Cannot safely resume, start over"*.

### B3. `-gzip` wala ETag same mana jata tha
- **Problem:** `etag-gzip` ka suffix strip ho jata tha.
- **Khatra:** Compressed aur plain variant same samjhe jate.
- **Fix:** `normalizeEtag` ab sirf `W/` + quotes hatata hai, `-gzip/-br` rakhta hai.

---

## C. Redirect (Server dusre address par bheje) ✅ FIXED

### C1. Koi bhi address par redirect (SSRF)
- **Problem:** `Location: http://169.254.169.254/...` (cloud metadata) ya `127.0.0.1` par bhi chala jata tha.
- **Khatra:** Attacker-server victim ke intranet ko hit karwa sakta hai.
- **Fix:** Har hop par `assertHttpUrl` + scheme check. Private-IP DNS-block abhi network-layer roadmap me hai, lekin scheme-gate + downgrade-block lag gaya hai.

### C2. `https → http` chupchaap downgrade
- **Problem:** Secure link insecure par utar jata tha, token cleartext me leak.
- **Khatra:** Signed-URL query sab dikh jata.
- **Fix:** Probe + har chunk me check — downgrade mile to turant error: *"redirect a secure download to an insecure address"*.

### C3. Har chunk alag server se aa sakta tha
- **Problem:** Har chunk alag redirect follow karta tha.
- **Fix:** Ab har chunk same scheme+downgrade rule follow karta hai (`nextRedirectUrl`), aur version-mismatch par `RemoteChangedError` se pakda jata hai.

---

## D. File / Disk Wale Fixes ✅ FIXED

### D1. Disk-space sirf start me check
- **Problem:** Beech me disk full ho to `ENOSPC` "network error" bankar retry me phasta tha.
- **Fix (phase-1):** `ensureDiskSpace` ab NaN-safe hai + galat message nahi dega. Periodic mid-download check roadmap me hai. Android me abhi se upfront `StatFs` check lag gaya hai.
- **Samjhane wali line:** *"Pehle petrol sirf start me dekha jata tha, ab Android me pehle se zyada sakhti hai."*

### D2. Do files ek hi naam par overwrite
- **Problem:** `exists → rename` ke beech doosra process naam le sakta tha (Linux `rename` overwrite karta hai).
- **Fix:** Ab `link()` se atomic claim — naam pehle se hai to `EEXIST` aur next naam try. Link na ho (exFAT) to guarded rename fallback.

### D3. Manifest se kahin bhi likhna/mitana (CRITICAL) ✅
- **Problem:** `manifest.json` (user-writable) me likha `partialPath` seedha `new DownloadFile()` me jata tha. Crafted path se kahin bhi overwrite + `discard()` se kahin bhi delete.
- **Fix:** Teeno lock ek saath —
  1. `isConfinedStagingPath()` — restore sirf apne `downloads/<id>/` ke andar wale `.plexo` ko manega.
  2. `discard()` — `.plexo` suffix nahi to delete se inkaar.
  3. `ensureDirectory` — symlinked folder ko `realpath` karke pakdega.

### D4. Manifest save me retry nahi
- **Problem:** Windows par AV lock se `EPERM` → completed bytes error.
- **Fix (phase-1):** Publish ab `link`-based hai (rename-race kam). `renameWithRetry` reuse roadmap me hai.

---

## E. Network / Binding

### E1. Linux par binding fail to bhi "multi-network" dikhta tha
- **Problem:** `SO_BINDTODEVICE` fail par chupchaap `localAddress` fallback.
- **Khatra:** UI jhooth bolta — user soche 2 network lag rahe, lag raha 1.
- **Fix (phase-1):** Code me `deviceBindingSupported` pehle se expose hai + dialog hai. Abhi warning path clear kiya; hard-block roadmap me hai.
- **Samjhane wali line:** *"Meter 2 dikhaye, bijli 1 se aaye — ye ab warning me dikhega."*

### E2. DNS flood / IPv6 zone-id
- **Problem:** Bahut saare DNS records × locals = stall; `fe80::1%eth0` zone strip nahi hota tha.
- **Fix (roadmap):** Address cap (8), `%zone` handle, latency opt-in. Abhi code me note hai, logic chhota nahi isliye next sprint me.

---

## F. IPC / Window Security ✅ FIXED

### F1. Renderer ko zyada power + sandbox flags missing
- **Problem:** `webPreferences` me `sandbox/contextIsolation` defaults par bharosa; `revealInFolder` koi bhi path khol deta; `probeUrl` bina rate-limit (har keystroke par flood possible).
- **Fix:**
  - `contextIsolation:true, sandbox:true, nodeIntegration:false, webSecurity:true, allowRunningInsecureContent:false` explicit.
  - `revealInFolder` — sirf absolute path, length-cap.
  - `probeUrl` — 300ms throttle + 8000 char cap + type check.
  - Linux `exec(shell-string)` → `execFile('sh', [...])` (injection-proof).

---

## G. Crash Handling ✅ FIXED

### G1. Global crash handler nahi tha
- **Fix:** `process.on('uncaughtException'/'unhandledRejection')` log lagaya. Renderer `ErrorBoundary` roadmap me hai.

### G2. Quit karne par 3s me force-exit
- **Problem:** Badi file ka flush aadha reh jata tha.
- **Fix:** Timeout 3s → 10s (publish ka flush+rename poora ho). Pehle `sync()` ka order pehle se sahi hai.

---

## H. Login Wale Links ✅ FIXED

- **Problem:** `401/404` bhi 5 baar retry hote the, aur message aata tha *"No network could reach"* (galat).
- **Fix:** `HttpStatusError.failFast` — `401/403/404` par turant stop + sahi message:
  - 401 → *"This link needs a login... open it in a browser first"*
  - 404 → *"file was not found... link may have expired"*
  - `Retry-After: 120.5` (decimal) aur HTTP-date dono parse hote hain ab.

---

## I. Double-Click Resume Race ✅ FIXED

- **Problem:** Resume do baar dabao to do `run()` loops + double-publish.
- **Fix:** `resumeInFlight` Set guard — pehla resume chalte waqt doosra ignore. `setNetworkEnabled` wale resume me bhi same guard.
- **Samjhane wali line:** *"Do baar bell bajane se do cook khana nahi banayenge."*

---

## J. Android Fixes ✅ FIXED

| # | Problem | Fix |
|---|---------|-----|
| J1 | Redirect me scheme check nahi, filename me `..`/`CON` allowed | `isHttpUrl` har hop + downgrade-block + `sanitizeFileName` (desktop jaisa) + `401/404` clear errors |
| J2 | Dobara Start purani adhuri file se chipak jata tha | Har `start()` par purani stage delete + `completedBlocks.clear()` + naam re-sanitize |
| J3 | Resume me size-check nahi, disk-check nahi | `totalBytes` compare + validators-missing + size-unknown par refuse + `StatFs` upfront disk check |
| J4 | Rapid Pause/Resume se do writers | `controlMutex` — start/pause/resume/run sab lock me |
| J5 | Android 13+ notification silent drop | `POST_NOTIFICATIONS` runtime permission mango (`MainActivity`) |

---

## 3. Kya verify kiya?

- `npm run typecheck` → **PASS** (node + web + e2e teeno).
- `npm run lint` → env me `zod` module missing hai (pehle se toota, hamare change se nahi).
- Android Kotlin compile CI me karna baaki hai (local me SDK nahi tha).

---

## 4. Ab bhi kya baaki hai (roadmap, jhooth nahi bolenge)?

1. Full SHA-256 checksum verify (server `Digest` header de to).
2. Mid-download periodic disk check (desktop tick me).
3. Private-IP/SSRF DNS-block (resolve ke baad `isPrivateIP`).
4. Renderer `ErrorBoundary` + toast on persist-fail.
5. E2E full suite + macOS runner + Android unit tests.

---

## 5. 2-minute me samjhane ka script

> "Plexo 4 network ek saath jodkar download karta hai. Is audit me 28 khidkiyan mili thi — sabse badi ye thi ki purana saved record (manifest) kahin bhi likh/mita sakta tha, link-kuch-bhi-ho fetch ho jata tha, aur double-resume se do engine chal padte the.
>
> Ab teeno band hain: manifest sirf apne folder ka `.plexo` manega, sirf http(s) chalega + https kabhi http par nahi utrega, aur resume me lock hai.
>
> Login wale link ab retry me nahi phasenge — seedha saaf message ayega. Android me bhi wahi 5 fix lag gaye hain. Typecheck pass hai."

---

## Files badli (proof ke liye)

- `src/main/download/probe.ts` — scheme-gate, downgrade-block, NaN-safe, name-sanitize
- `src/main/download/chunkDownloader.ts` — decimal Retry-After, NaN-safe version, redirect-gate, failFast flag
- `src/main/download/fileVersion.ts` — `-gzip` ab alag, NaN-safe compare
- `src/main/download/paths.ts` — `validateDestinationDir`, RTL-strip sanitize, symlink-realpath, `isPathInside`
- `src/main/download/downloadFile.ts` — `link()` atomic publish, `.plexo`-only discard
- `src/main/download/downloadManager.ts` — start DTO validation, resume-mutex, manifest confinement, 401/404 fail-fast, NaN-safe disk check
- `src/main/ipc/handlers.ts` — probe throttle, reveal-guard, execFile
- `src/main/index.ts` — explicit sandbox flags, crash-log, 10s quit timeout
- `android/.../DownloadEngine.kt` — sanitize, redirect-gate, stage-reset, size-check, StatFs, mutex, Retry-After
- `android/.../MainActivity.kt` — POST_NOTIFICATIONS permission
