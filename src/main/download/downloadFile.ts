import { createWriteStream, type WriteStream } from 'node:fs'
import { link, lstat, open, rename, rm, stat, unlink } from 'node:fs/promises'
import { basename, dirname, extname, join } from 'node:path'

/** Whether `path` exists. Only ENOENT means "no"; any other error is the caller's problem. */
async function exists(path: string): Promise<boolean> {
  return lstat(path).then(
    () => true,
    (error: NodeJS.ErrnoException) => {
      if (error.code === 'ENOENT') return false
      throw error
    }
  )
}

/** The only large file owned by a download. It lives beside the final file so publishing it
 * requires no copy and never needs a second file's worth of disk space. */
export class DownloadFile {
  constructor(readonly path: string) {}

  /** Every writer has its own descriptor and explicit offset. Never use append mode here. */
  writer(position: number): WriteStream {
    // The staging path is claimed with O_EXCL at reserve time and never follows
    // a user-supplied symlink: restore paths are confined before constructing.
    return createWriteStream(this.path, { flags: 'r+', start: position })
  }

  async read(position: number, length: number): Promise<Buffer> {
    const handle = await open(this.path, 'r')
    try {
      const buffer = Buffer.alloc(length)
      const { bytesRead } = await handle.read(buffer, 0, length, position)
      return buffer.subarray(0, bytesRead)
    } finally {
      await handle.close()
    }
  }

  /** Flushes the staging file before a recovery checkpoint or final publication. */
  async sync(): Promise<void> {
    const handle = await open(this.path, 'r+')
    try {
      await handle.sync()
    } finally {
      await handle.close()
    }
  }

  async size(): Promise<number> {
    return (await stat(this.path)).size
  }

  async publish(
    destinationPath: string,
    expectedBytes: number,
    beforeAttempt: (candidate: string) => Promise<void>
  ): Promise<string> {
    if (expectedBytes > 0 && (await this.size()) !== expectedBytes) {
      throw new Error('Download file size does not match the expected size')
    }
    await this.sync()
    const extension = extname(destinationPath)
    const stem = basename(destinationPath, extension)
    const directory = dirname(destinationPath)
    for (let index = 0; index < 10_000; index += 1) {
      const suffix = index === 0 ? '' : ` (${index})`
      if (Buffer.byteLength(`${suffix}${extension}`) >= 255) {
        throw new Error('The file extension is too long')
      }
      const candidateCharacters = Array.from(stem)
      while (Buffer.byteLength(`${candidateCharacters.join('')}${suffix}${extension}`) > 255) {
        candidateCharacters.pop()
      }
      const candidateStem = candidateCharacters.join('')
      const candidate = join(directory, `${candidateStem}${suffix}${extension}`)
      if (candidate !== destinationPath && (await exists(`${candidate}.plexo`))) continue
      await beforeAttempt(candidate)
      // Atomically claim the name without ever overwriting: link() fails with
      // EEXIST when someone else already owns it (POSIX rename would silently
      // replace it). Then remove the staging name, leaving only the final one.
      try {
        await link(this.path, candidate)
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code === 'EEXIST') continue
        // Filesystems without hardlinks (some exFAT/FAT mounts, cross-device):
        // fall back to the guarded rename below.
        if ((error as NodeJS.ErrnoException).code !== 'EPERM' && (error as NodeJS.ErrnoException).code !== 'EXDEV') throw error
        // Check immediately before the rename, after the recovery intent is saved.
        if (await exists(candidate)) continue
        try {
          await rename(this.path, candidate)
          return candidate
        } catch (renameError) {
          if ((renameError as NodeJS.ErrnoException).code === 'EEXIST') continue
          throw renameError
        }
      }
      await unlink(this.path).catch(() => {})
      return candidate
    }
    throw new Error('Could not find an unused file name for this download')
  }

  async discard(): Promise<void> {
    // Never delete anything but our own staging file: a crafted manifest path
    // must not turn cancel/remove into an arbitrary-file delete.
    if (!this.path.endsWith('.plexo')) {
      throw new Error('Refusing to delete a file that is not a download staging file')
    }
    await rm(this.path, { force: true })
  }
}
