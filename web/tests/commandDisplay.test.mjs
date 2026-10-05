import assert from 'node:assert/strict'
import test from 'node:test'
import { commandForDisplay } from '../src/commandDisplay.ts'

test('Unicode JSON escapes and supplementary characters are readable', () => {
  assert.equal(commandForDisplay(String.raw`{"phase":"\u51c6\u5907","message":"\u4e2d\u6587"}`), '{"phase":"准备","message":"中文"}')
  assert.equal(commandForDisplay(String.raw`'\uD83D\uDE00 \u{1f680}'`), "'😀 🚀'")
})

test('UTF-8 octal and hex runs decode while control and shell escapes stay literal', () => {
  assert.equal(commandForDisplay(String.raw`printf '%b' '\122\103\115\040\344\270\255\346\226\207\012'`), String.raw`printf '%b' 'RCM 中文\012'`)
  assert.equal(commandForDisplay(String.raw`'\xe4\xb8\xad\xe6\x96\x87'`), "'中文'")
  assert.equal(commandForDisplay(String.raw`'\044\344\270\255\047\042\134'`), String.raw`'\044中\047\042\134'`)
})

test('incomplete, binary, legacy and ASCII-only byte escapes are not guessed', () => {
  for (const value of [String.raw`'\344\270'`, String.raw`'\255'`, String.raw`'\377\377'`,
    String.raw`'\xc0\xaf'`, String.raw`'\xd6\xd0'`, String.raw`'\101\102'`]) {
    assert.equal(commandForDisplay(value), value)
  }
})

test('literal paths and escaped backslashes retain their exact spelling', () => {
  for (const value of [String.raw`C:\logs\u4e2d\u6587`, String.raw`/tmp/\u4e2d\u6587`,
    String.raw`C:\logs\344\270\255`, String.raw`'\\u4e2d\\u6587'`, String.raw`'\\344\270\255'`]) {
    assert.equal(commandForDisplay(value), value)
  }
})

test('control characters, bidi marks, lone surrogates and invalid code points stay escaped', () => {
  for (const value of [String.raw`\u000a`, String.raw`\u0027`, String.raw`\u0085`, String.raw`\u202e`,
    String.raw`\u2028`, String.raw`\uD800`, String.raw`\uDC00`, String.raw`\u{110000}`]) {
    assert.equal(commandForDisplay(value), value)
  }
  assert.equal(commandForDisplay(String.raw`'\357\273\277\344\270\255'`), String.raw`'\357\273\277中'`)
})

test('ordinary command text and markup are retained as text', () => {
  for (const value of ['git status --short', 'echo 中文', '<script>alert(1)</script>', '']) {
    assert.equal(commandForDisplay(value), value)
  }
})
