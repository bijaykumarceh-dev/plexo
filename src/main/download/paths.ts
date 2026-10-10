import { app } from 'electron'
import { lstat, mkdir, open, realpath, rm, stat } from 'node:fs/promises'
import { basename, extname, isAbsolute, join, normalize, resolve, sep } from 'node:path'

export function getDefaultDownloadsDir(): string {
  return app.getPath('downloads')
}

export function getHomeDir(): string {
  return app.getPath('home')
}

// Somewhere to stop rather than spin forever if every candidate is taken
// (or if something outside the app is creating them as fast as we try).
const MAX_NAME_ATTEMPTS = 10_000

async function pathExists(path: string): Promise<boolean> {
  try {
    await lstat(path)
    return true
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return false
    throw error
  }
}

export function sanitizeFileName(fileName: string): string {
  // A server-provided name is one component, never a path or NTFS data stream.
  // Strip direction overrides (RTL attack) first so `evil\u202Efdp.exe` can't pose as `evilpdf.exe`.
  let safe = fileName
    .replace(/[\u202A-\u202E\u200E\u200F\u2066-\u2069]/g, '')
    .replace(/[\\/]/g, '_')
    .replace(/\p{Cc}/gu, '_')
    .trim()
  if (process.platform === 'win32') {
    safe = safe.replace(/[<>:"|?*]/g, '_').replace(/[ .]+$/, '')
    if (/^(con|prn|aux|nul|com[1-9¹²³]|lpt[1-9¹²³])(?:\.|$)/i.test(safe)) safe = `_${safe}`
  }
  return !safe || /^\.+$/.test(safe) ? 'download' : safe
}

/** Rejects a destination folder the app must never write to: relative paths,
 * `~` shortcuts, drive roots and empty strings. Call before mkdir/disk-check. */
export function validateDestinationDir(directory: string): void {
  if (!directory || directory.trim() !== directory || directory.includes('~')) {
    throw new Error('Pick a valid download folder first')
  }
  if (!isAbsolute(directory)) {
    throw new Error('The download folder must be an absolute path')
  }
  const normalized = normalize(directory)
  // Bare drive roots (C:\, /) are never a download target.
  if (/^[A-Za-z]:[\\/]?$/.test(normalized) || normalized === '/') {
    throw new Error('Pick a folder inside a drive, not the drive itself')
  }
}

/** True when `child` really lives inside `parent` (after symlinks resolve).
 * Used to confine manifest-provided paths before any write/delete. */
export async function isPathInside(child: string, parent: string): Promise<boolean> {
  try {
    const [realChild, realParent] = await Promise.all([realpath(child).catch(() => resolve(child)), realpath(parent)])
    const root = join(realParent, '.')
    return realChild === root || realChild.startsWith(`${root}${sep}`)
  } catch {
    return false
  }
}

export async function ensureDirectory(directory: string): Promise<void> {
  validateDestinationDir(directory)
  try {
    // Refuse to download through a symlinked folder without noticing:
    // resolve it so staging + publish operate on the real location.
    const st = await lstat(directory).catch(() => null)
    if (st?.isSymbolicLink()) {
      directory = await realpath(directory)
    }
    await mkdir(directory, { recursive: true })
  } catch (error) {
    // Windows throws EPERM when trying to mkdir a drive root
    if ((error as NodeJS.ErrnoException).code === 'EPERM') {
      const stats = await stat(directory).catch(() => null)
      if (stats?.isDirectory()) return
    }
    throw error
  }
}

/**
 * Claims a download by exclusively creating <final name>.plexo. The final name
 * itself does not appear until the file is complete. An existing partial file
 * also claims its corresponding final name, including after a restart.
 */
export async function reserveDestinationPath(directory: string, fileName: string): Promise<string> {
  validateDestinationDir(directory)
  await ensureDirectory(directory)
  fileName = sanitizeFileName(fileName)
  const ext = extname(fileName)
  // Leave room for " (9999).plexo" within the usual 255-byte component limit.
  const suffixRoom = Buffer.byteLength(' (9999).plexo')
  const maxBaseBytes = 255 - suffixRoom - Buffer.byteLength(ext)
  if (maxBaseBytes <= 0) throw new Error('The file extension is too long')
  const baseCharacters = Array.from(basename(fileName, ext))
  while (Buffer.byteLength(baseCharacters.join('')) > maxBaseBytes) baseCharacters.pop()
  const base = baseCharacters.join('')

  for (let counter = 0; counter < MAX_NAME_ATTEMPTS; counter += 1) {
    const candidate =
      counter === 0
        ? join(directory, `${base}${ext}`)
        : join(directory, `${base} (${counter})${ext}`)
    if (await pathExists(candidate)) continue
    try {
      const handle = await open(`${candidate}.plexo`, 'wx+')
      await handle.close()
      if (await pathExists(candidate)) {
        await rm(`${candidate}.plexo`)
        continue
      }
      return candidate
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error
    }
  }

  throw new Error(`Could not find an unused file name for "${fileName}" in ${directory}`)
}
