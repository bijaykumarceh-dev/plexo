/** What a server says about which version of the file it's serving. */
export interface FileVersion {
  etag: string | null
  lastModified: string | null
  /** 0 = unknown. */
  totalBytes: number
}

/**
 * How a response's version compares with the ones a download has accepted:
 * - 'same': nothing suggests a different file.
 * - 'size': the size differs. Identical bytes can't have a different size, so this is proof.
 * - 'validators': the ETag or Last-Modified differs. That's only a hint — load-balanced servers
 *   can label the same bytes differently — so it has to be settled by comparing actual bytes.
 */
export type VersionCheck =
  { kind: 'same' } | { kind: 'size'; detail: string } | { kind: 'validators'; detail: string }

/** Drops what varies without the bytes changing: the weak-validator marker and the
 * quotes. Suffixes like `-gzip` are deliberately kept: a gzip variant is not proven
 * to be identical bytes until a sample check says so. */
export function normalizeEtag(etag: string): string {
  return etag
    .trim()
    .replace(/^W\//i, '')
    .replace(/^"(.*)"$/, '$1')
}

function finiteSize(n: number): number {
  return Number.isFinite(n) && n >= 0 ? n : 0
}

/** `accepted` is the version the download started on, plus any whose bytes were since
 * confirmed identical to it. */
export function compareVersion(accepted: FileVersion[], seen: FileVersion): VersionCheck {
  const expectedSize = finiteSize(accepted[0]?.totalBytes ?? 0)
  const seenSize = finiteSize(seen.totalBytes ?? 0)
  if (expectedSize > 0 && seenSize > 0 && seenSize !== expectedSize) {
    return { kind: 'size', detail: `it is now ${seenSize} bytes instead of ${expectedSize}` }
  }

  const etags = accepted.flatMap((version) => (version.etag ? [normalizeEtag(version.etag)] : []))
  if (seen.etag && etags.length > 0) {
    return etags.includes(normalizeEtag(seen.etag))
      ? { kind: 'same' }
      : { kind: 'validators', detail: `its ETag is now ${seen.etag}` }
  }

  const dates = accepted.flatMap((version) => (version.lastModified ? [version.lastModified] : []))
  if (seen.lastModified && dates.length > 0 && !dates.includes(seen.lastModified)) {
    return { kind: 'validators', detail: `it was modified at ${seen.lastModified}` }
  }
  return { kind: 'same' }
}
