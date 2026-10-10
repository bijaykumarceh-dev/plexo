# Plexo — Sare Loopholes Audit Report

> Date: 2026-10-10 | Version: 1.0.0-rc.11 | Auditor: Muse Spark (automated code audit)
> Ye file is project ke sabhi confirmed loopholes / gaps ka record hai. Har issue ko source code padhkar confirm kiya gaya hai.

## Project kya hai (short me)

Plexo ek multi-network download manager hai (Electron + React + TS desktop, Kotlin Android preview).
Core idea: file ko 8MB chunks me todo, har network interface (Wi-Fi, Ethernet, USB-tether, Cellular) par alag-alag HTTP Range (`206 Partial Content`) workers chalao, dynamic work-stealing queue se fast network zyada chunks le. Pause/resume, ETag/Last-Modified check, stall watchdog, redirect follow, telemetry/grid UI.

---

## Severity Legend

- **Critical** = data loss / arbitrary file overwrite-delete / turant fix karo
- **High** = corruption / SSRF / security bypass / galat resume
- **Medium** = crash / retry-storm / misleading UI / flaky behavior
- **Low** = minor leak / polish / perf

---

## A. Input Validation / Path Traversal

### A1. [High] URL scheme allow-list nahi hai — `file:`, `ftp:` bhi HTTP jaise fetch ho jata hai
- **Kahan:** `src/main/download/probe.ts:31` (`https: ? httpsRequest : httpRequest`), `src/main/download/probe.ts:113` (`new URL(rawUrl)` bina check), `src/main/ipc/handlers.ts:136`, `src/main/download/downloadManager.ts:659`, `src/main/network/routes.ts:392`
- **Kya hota hai:** Renderer se aayi koi bhi string probe/start me chali jati hai. `file://`, `ftp://`, `gopher://` bhi `httpRequest` se fetch hone ki koshish hogi.
- **Fix:** `probeUrl()` aur `manager.start()` ke start me:
  ```ts
  const u = new URL(rawUrl);
  if (u.protocol !== 'http:' && u.protocol !== 'https:') throw new Error('Only http(s) supported');
  ```

### A2. [High] `destinationDir` validate nahi hota (arbitrary write)
- **Kahan:** `src/main/download/downloadManager.ts:667-668`, `src/main/ipc/handlers.ts:155`
- **Kya hota hai:** Renderer ka bheja `destinationDir` bina `isAbsolute` / `isDirectory` check ke `mkdir` ho jata hai. Compromised renderer kahin bhi likh sakta hai.
- **Fix:** main me check karo — `path.isAbsolute()`, `stat().isDirectory()`, `~` / relative / root reject karo.

### A3. [High] `Content-Length` / `Content-Range` me `NaN` to instant-fake-complete
- **Kahan:** `src/main/download/probe.ts:171` (`Number(contentLength)`), `src/main/download/chunkDownloader.ts:108`, `src/main/download/fileVersion.ts:33`, `src/renderer/src/screens/IdleScreen.tsx:204` (`?? 0` NaN ko nahi pakadta)
- **Kya hota hai:** Server `"abc"` bhej de to `totalBytes = NaN` → `planBlocks(NaN)` → 0 blocks → turant "complete" empty file publish.
- **Fix:** `Number.isFinite(n) ? n : 0/null` dono jagah lagao.

### A4. [Low] `Content-Disposition` filename bina sanitize UI me dikhta hai
- **Kahan:** `src/main/download/probe.ts:58-92` → `src/main/download/paths.ts:27-35`
- **Kya hota hai:** `%2e%2e%2f` (`../`), RTL override `\u202E` wala naam probe result me raw aata hai, sanitize sirf publish time par hota hai.
- **Fix:** Probe me hi sanitize karo, `\u202E` strip karo, 255 byte me trim karo.

---

## B. Resume Integrity

### B1. [High] No checksum — sirf size check par publish
- **Kahan:** `src/main/download/downloadFile.ts:56-58`
- **Kya hota hai:** Sahi length lekin galat bytes (bit-flip / CDN ne galat slice diya) pakda nahi jayega.
- **Fix:** Agar server `Digest` / `Content-MD5` de to verify karo, nahi to completion par `confirmSameBytes` jaisi sample-hash dobara chalao.

### B2. [High] Validator-less server hamesha "same" mana jata hai
- **Kahan:** `src/main/download/fileVersion.ts:44-48`, `src/main/download/chunkDownloader.ts:301-306`
- **Kya hota hai:** Na ETag na Last-Modified aur size unknown ho to badli hui file bhi resume ho jayegi → silent corruption.
- **Fix:** Dono validators null + size 0 ho to force sample-check ya resume refuse karo (`discard=false` + warning).

### B3. [Medium] `normalizeEtag` me `-gzip` strip karna galat ho sakta hai
- **Kahan:** `src/main/download/fileVersion.ts:21-27`
- **Kya hota hai:** `etag-gzip` aur plain etag same mane jate hain jabki bytes alag ho sakte hain.
- **Fix:** Sirf `W/` + quotes strip karo, `-gzip/-br` ko alag samjho jab tak `Content-Encoding` verify na ho.

---

## C. Redirect / SSRF

### C1. [High] Open redirect + SSRF — private IP block nahi hai
- **Kahan:** `src/main/download/probe.ts:122`, `src/main/download/chunkDownloader.ts:360,397`, `src/main/network/routes.ts:246-250`, `src/main/network/deviceBinding.ts:69-70`
- **Kya hota hai:** Attacker server `Location: http://169.254.169.254/...` ya `http://127.0.0.1/` par redirect kar de to Plexo intranet/metadata ko hit karega. `finalUrl` wahi ban jayega (`src/shared/types.ts:26`).
- **Fix:** Sirf `http(s):` allow karo, DNS ke baad `isPrivateIP()` check lagao (loopback/link-local/private/metadata `169.254.169.254` block).

### C2. [High] Silent `https → http` downgrade
- **Kahan:** Upar wali lines + `src/main/network/routes.ts:392,405`
- **Kya hota hai:** Bina warning ke https se http par utar jata hai — Range tokens / signed URL query cleartext me leak.
- **Fix:** Downgrade reject karo ya user se confirm karwao.

### C3. [Medium] Har chunk alag host se aa sakta hai (rebinding stitch)
- **Kahan:** `src/main/download/chunkDownloader.ts:75,340-362`
- **Kya hota hai:** Har chunk independently redirect follow karta hai — ek file ke tukde alag-alag IP/origin se jud sakte hain bina dobara version check ke.
- **Fix:** Expected host pin karo, host change par warning + `compareVersion` dobara.

---

## D. Disk / File Races

### D1. [High] Disk-space sirf start me check (TOCTOU)
- **Kahan:** `src/main/download/downloadManager.ts:454-463,668`
- **Kya hota hai:** Beech me disk full ho to `ENOSPC` generic `ConnectionError` retry me phas jata hai (5 retry, phir "network failed" galat message).
- **Fix:** `run()` tick me periodic `statfs` karo, `.plexo` ka existing size ghatao, turant "disk full" error do.

### D2. [High] Publish me `exists → rename` race (POSIX overwrite)
- **Kahan:** `src/main/download/downloadFile.ts:79-83` (comment `76-78` khud manta hai)
- **Kya hota hai:** Linux par `rename` silently overwrite karta hai — do process same naam par publish kare to ek file gayab.
- **Fix:** `open(candidate,'wx')` se claim karo ya `link()+unlink()` atomic no-replace use karo.

### D3. [Critical] Symlink follow + manifest se arbitrary path overwrite/delete
- **Kahan:** `src/main/download/downloadFile.ts:22-24` (`createWriteStream r+` symlink follow), `src/main/download/paths.ts:73`, `src/main/download/downloadManager.ts:505-536` (manifest se `partialPath/publicationPath/destinationPath` bina confinement)
- **Kya hota hai:** User-writable `manifest.json` me crafted path likh diya to `writer()` kahin bhi overwrite aur `discard()` (`rm`) kahin bhi delete kar sakta hai.
- **Fix:** `realpath` karke verify karo `partialPath` download root ke andar hai, `O_NOFOLLOW` se open karo.

### D4. [Medium] Manifest persist me retry/fsync nahi
- **Kahan:** `src/main/download/downloadManager.ts:2148-2150` vs `src/main/jsonFile.ts:27-37` (`renameWithRetry`)
- **Kya hota hai:** Windows par AV/indexer `EPERM` de to completed bytes error ban jate hain.
- **Fix:** `renameWithRetry` reuse karo + dir fsync.

---

## E. Network / DNS / Binding

### E1. [Medium] Linux par binding fail → silent fallback (UI jhooth bolega)
- **Kahan:** `src/main/network/deviceBinding.ts:59-62,70-72`, `src/main/ipc/handlers.ts:79`
- **Kya hota hai:** `SO_BINDTODEVICE` fail par `localAddress` fallback, lekin UI ab bhi "multi-network in use" dikhata hai.
- **Fix:** `deviceBindingSupported=false` ho to multi-network start block karo + `NetworkBindingDialog` dikhao.

### E2. [Medium/Low] DNS flood, IPv6 zone-id bug, latency spam
- **Kahan:** `src/main/network/routes.ts:32-40,90-117,158,221`, `src/main/network/interfaces.ts:125-132`, `src/main/network/latency.ts:4-53`
- **Kya hota hai:** Unbounded DNS records → locals×remotes cross-product, 250ms stagger se stall; `fe80::1%eth0` zone strip nahi hota → bogus lookup; latency check har local×remote combo par socket kholta hai.
- **Fix:** Addresses cap karo (e.g. 8), `%zone` handle karo, latency opt-in banao.

---

## F. IPC / Preload Security

### F1. [High] Preload over-broad + sender check nahi + sandbox flags missing
- **Kahan:** `src/preload/index.ts:21-44`, `src/main/index.ts:41-45` (sirf `preload/backgroundThrottling`, `contextIsolation/sandbox` defaults par bharosa), `src/main/ipc/handlers.ts:44-55,136,151-155`
- **Kya hota hai:** Koi bhi renderer code `probeUrl/startDownload/revealInFolder/readClipboard/clipboard` chala sakta hai. `revealInFolder(filePath)` arbitrary path probe, `probeUrl` rate-limit nahi (IdleScreen har 600ms probe karta hai — `IdleScreen.tsx:97-106`).
- **Fix:** `BrowserWindow` me `contextIsolation:true, sandbox:true, nodeIntegration:false, webSecurity:true` explicit karo; `event.senderFrame === mainWindow` check karo; `filePath` known destination ke andar verify karo; `probeUrl` throttle karo.

### F2. [Medium] Linux `openNetworkSettings` me `exec` (shell)
- **Kahan:** `src/main/ipc/handlers.ts:26`
- **Kya hota hai:** Aaj static string hai (safe), lekin `exec` shell hai — kal ko variable juda to injection.
- **Fix:** `execFile`/`spawn` use karo.

---

## G. Crash / Error Handling

### G1. [Medium] Global `uncaughtException/unhandledRejection` handler nahi
- **Kahan:** `src/main/*` me koi `process.on(...)` nahi; `src/main/ipc/handlers.ts:163-165` (`void resumeAfterVerifying`), `src/renderer/src/main.tsx:7-11`, `src/renderer/src/App.tsx:80-92`
- **Kya hota hai:** `reconcile` ka `Unknown network` throw ya publish throw unhandled ban sakta hai; renderer me `ErrorBoundary` nahi.
- **Fix:** Main me global handlers + `failDownload`, renderer me `ErrorBoundary` lagao.

### G2. [Medium] `before-quit` 3s force-exit se torn writes
- **Kahan:** `src/main/index.ts:101-116`, `src/main/download/downloadManager.ts:729-752`
- **Kya hota hai:** `pause()` ka `runPromise` (20s watchdog) 3s me pura nahi hota to `file.sync()/persistNow` aadha reh jata hai. Recovery hai (tmp delete + clamp `savedProgress.ts:57-60`) lekin slow.
- **Fix:** `stopRun` se pehle `file.sync()` karo, `publishing` ho to timeout badhao.

### G3. [Low] Silent swallows — failure invisible rehta hai
- **Kahan:** `src/main/download/downloadManager.ts:2152-2155` (`persistenceChain.catch(()=>{})`), `src/renderer/src/store/useAppStore.ts:78` (`persist().catch(()=>{})`), `src/main/updateCheck.ts:44`, `src/main/network/interfaces.ts:37,75`
- **Kya hota hai:** Checkpoint/theme/update/networksetup fail hone par UI ko pata nahi chalta.
- **Fix:** N fails ke baad toast/log dikhao, telemetry me count karo.

---

## H. Auth / Rate-limit

### H1. [Medium] No cookies/auth — 401/404 bhi retry hota hai
- **Kahan:** `src/main/download/chunkDownloader.ts:54,204,391`, `src/main/download/probe.ts:38`, `src/main/download/downloadManager.ts:1766`, `src/main/download/chunkDownloader.ts:59-65`
- **Kya hota hai:** Headers me sirf `User-Agent + Range`. Login wale URL `403 → refused` me "No network could reach" galat diagnosis dete hain. `401` fail-fast nahi. `Retry-After: 120.5` ya date-with-space parse nahi hota (`/^\d+$/` only).
- **Fix:** `401/404/unexpected-416` par fail-fast karo, decimal/date `Retry-After` support karo, "no auth/cookies" README me clearly likho.

---

## I. Concurrency

### I1. [High] Double-click Resume se double `run()` loop (double-publish)
- **Kahan:** `src/main/download/downloadManager.ts:755-762,772,829,834-860`
- **Kya hota hai:** `resume()` sync `void resumeAfterVerifying` hai — do baar rapid call par dono `await runPromise` karke dono `runPromise = this.run()` set kar dete hain → 2 loops + double workers.
- **Fix:** `if (resuming) return` guard + mutex/chain (jaise `persistenceChain`).

### I2. [Low] Hedge + refresh milkar 4x duplicate bytes
- **Kahan:** `src/main/download/scheduler.ts:64-138`, `src/main/download/downloadManager.ts:1285-1318`
- **Kya hota hai:** Bug nahi, lekin `hedgesUsed` cap 2 + `refreshesByBlock` cap 2 alag-alag → worst-case ek block 4 baar fetch.
- **Fix:** Global duplicate-byte budget lagao.

---

## J. Android (Kotlin) Loopholes

### J1. [High] Redirect downgrade + SSRF + weak filename sanitize
- **Kahan:** `android/.../DownloadEngine.kt:109-122,134-136`, `MainActivity.kt:146`, `DownloadEngine.kt:419-422`
- **Kya hota hai:** 6-hop redirect me scheme check nahi, `https→http` allowed, private-IP block nahi. Filename me sirf `[\\/:*?"<>|]` replace — control chars, `..`/`.`, `CON/NUL`, trailing space/dot, 255-byte cap missing. `safeName=".."` → parent folder.
- **Fix:** Desktop wala `sanitizeFileName` port karo.

### J2. [High] Stage-file reuse se corruption
- **Kahan:** `DownloadEngine.kt:158-162`
- **Kya hota hai:** Bina `cancel()` ke dobara `start()` purani file + stale `completedBlocks` reuse karta hai → galat offsets.
- **Fix:** Har `start()` par `stageFile/completedBlocks` reset karo.

### J3. [High] Resume desktop se kamzor + disk check nahi
- **Kahan:** `DownloadEngine.kt:178-188,217,224-230,260-276,327-331`, disk check absent (desktop `ensureDiskSpace` ka equivalent nahi)
- **Kya hota hai:** Sirf etag/lastModified equality, `totalBytes` change ignore, dono null to re-probe hi nahi. Partial-block bytes discard karke full block dobara. `If-Range` sirf ETag. `Retry-After` ignore (fixed `1s<<n`). `setLength + copyTo` ko ~2x space chahiye, `ENOSPC` generic error.
- **Fix:** `totalBytes` compare karo, per-block bytes persist karo (desktop `savedProgress` jaisa), `StatFs` pre-check lagao.

### J4. [Medium] `start/pause/resume` coroutine race
- **Kahan:** `DownloadEngine.kt:152-191,204-207,252,291`
- **Kya hota hai:** `job` plain `var`, `Mutex` nahi — rapid taps par purana `job` overwrite, do `perform` writers interleave.
- **Fix:** `Mutex.withLock` me wrap karo.

### J5. [Low] Notification permission + binder over-expose
- **Kahan:** `AndroidManifest.xml:13`, `DownloadService.kt:20-24`, `MainActivity.kt:76`
- **Kya hota hai:** `POST_NOTIFICATIONS` Android 13+ par runtime me manga hi nahi → notification silent drop. `LocalBinder` poora `engine` expose karta hai.
- **Fix:** Runtime permission mango, binder minimal rakho.

---

## K. Test / Build / Docs Gaps (loophole nahi, lekin risk)

- E2E 21 specs strong hain, lekin Android ke **zero tests** (`android/app/src/test` absent).
- CI PR par sirf `@smoke` Ubuntu par; macOS runner nahi → `networksetup` / `Get-NetAdapter` paths untested.
- `electron-builder.yml` me auto-update `https://example.com/auto-updates` placeholder hai — real mechanism sirf manual GitHub check (`updateCheck.ts`).
- Sab builds unsigned (`notarize:false`, no Win cert).
- `android/app/build/.../app-debug.apk` + `.gradle/.kotlin/` repo me committed hain — gitignore hone chahiye.
- `package.json` me `build:mac/linux` typecheck skip karte hain, `build:android`/`test:android` scripts nahi hain. Android `versionCode 1` vs desktop `rc.11` stale hai.
- README kehta hai "no pre-built releases" lekin `docs/` downloads page releases feed assume karta hai.

---

## Priority Fix Order (suggested)

1. **D3** manifest path confinement + `O_NOFOLLOW` (Critical)
2. **A1+A2+C1+C2+F1** scheme allow-list + destination check + SSRF/private-IP block + downgrade block + sandbox flags (High, ek saath)
3. **I1** resume mutex (High, chhota fix)
4. **A3** NaN guard (High, 2-line fix)
5. **B1+B2** checksum/sample-verify (High)
6. **D1+D2** disk tick + atomic publish (High)
7. **J1-J3** Android parity fixes (High)
8. Baaki Medium/Low roadmap me.

---

## Future Scope (kya add ho sakta hai — alag se ROADMAP ban sakta hai)

Download core: checksum verify (SHA-256), speed limiter, queue (multiple files), scheduler (night-time), torrent/magnet, HLS/DASH segments, FTP/SFTP/S3/WebDAV, browser extension (Chrome/Edge "Send to Plexo"), clipboard auto-catch, proxy/PAC + auth, cookies/login (session import), HTTP/3 QUIC experiment, IPv6-only networks, per-host connection profiles.

App/UX: download history + search, categories/folders, auto-rename rules, virus-scan hook (Defender API), notifications with actions (Open folder), tray + mini-mode, CLI (`plexo add <url>`), portable mode, auto-update (electron-updater + signed builds), crash-reporter + opt-in telemetry.

Android parity: relaunch recovery, live network join/leave, ETA/telemetry, per-stream table, disk check, background WorkManager, Share-intent ("Share → Plexo"), SD-card destination.

> Note: Ye audit static code reading par based hai, full runtime pentest nahi. Fix ke baad `npm run test:e2e` + nightly chaos (`PLEXO_CHAOS_RUNS=50`) dobara chalao.
