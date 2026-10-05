const utf8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true })

function escapedBackslash(text: string, offset: number): boolean {
  let count = 0
  while (offset > 0 && text[--offset] === '\\') count++
  return count % 2 === 1
}

function inLiteralPath(text: string, offset: number): boolean {
  let start = offset
  while (start > 0 && !/[\s'"`]/.test(text[start - 1])) start--
  return /^(?:[a-z]:[\\/]|\\\\|\/|\.{1,2}\/|~\/)/i.test(text.slice(start, offset))
}

function printable(code: number): boolean {
  return code >= 0x20 && !(code >= 0x7f && code <= 0x9f)
    && !(code >= 0xd800 && code <= 0xdfff) && code <= 0x10ffff
    && ![0x061c, 0xfeff].includes(code) && !(code >= 0x200b && code <= 0x200f)
    && !(code >= 0x2028 && code <= 0x202e) && !(code >= 0x2066 && code <= 0x2069)
}

/** A display-only preview. Never use this value to execute or copy a command. */
export function commandForDisplay(command: string): string {
  const bytesDecoded = command.replace(/(?:\\(?:[0-3][0-7]{2}|x[\da-fA-F]{2})){2,}/g, (raw, offset: number) => {
    if (escapedBackslash(command, offset) || inLiteralPath(command, offset)) return raw
    const parts = raw.match(/\\(?:[0-3][0-7]{2}|x[\da-fA-F]{2})/g) ?? []
    const bytes = Uint8Array.from(parts, (part) => part[1] === 'x'
      ? parseInt(part.slice(2), 16) : parseInt(part.slice(1), 8))
    if (!bytes.some((byte) => byte >= 0x80)) return raw
    try {
      const decoded = utf8.decode(bytes)
      let index = 0
      let result = ''
      for (const character of decoded) {
        const code = character.codePointAt(0)!
        const length = code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4
        // Preserve control escapes and shell delimiters. Only the visual
        // representation changes, even for a valid UTF-8 byte sequence.
        result += printable(code) && !['\\', "'", '"', '`', '$'].includes(character)
          ? character : parts.slice(index, index + length).join('')
        index += length
      }
      return result
    } catch {
      // Incomplete chunks, arbitrary binary bytes and legacy encodings are
      // not guessed. In particular, a partial UTF-8 character stays escaped.
      return raw
    }
  })
  return bytesDecoded.replace(/\\u(?:d[89ab][\da-f]{2}\\ud[c-f][\da-f]{2}|[\da-f]{4}|\{[\da-f]{1,6}\})/gi, (raw, offset: number) => {
    if (escapedBackslash(bytesDecoded, offset) || inLiteralPath(bytesDecoded, offset)) return raw
    let code: number
    if (raw.startsWith('\\u{')) code = parseInt(raw.slice(3, -1), 16)
    else if (raw.length === 12) {
      code = 0x10000 + ((parseInt(raw.slice(2, 6), 16) - 0xd800) << 10)
        + parseInt(raw.slice(8), 16) - 0xdc00
    } else code = parseInt(raw.slice(2), 16)
    return code > 0x7f && printable(code) ? String.fromCodePoint(code) : raw
  })
}
