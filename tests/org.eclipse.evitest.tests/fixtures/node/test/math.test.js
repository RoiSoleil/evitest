import assert from 'node:assert'
import { describe, it, test } from 'node:test'

describe('math', () => {
  describe('adds', () => {
    it('one and one', () => {
      assert.strictEqual(1 + 1, 2)
    })

    it('two and two', () => {
      assert.strictEqual(2 + 2, 4)
    })
  })

  it.skip('skipped', () => {})
  it.todo('later')

  it('compares objects', () => {
    assert.deepStrictEqual({ a: 1, b: 2 }, { a: 1, b: 3 })
  })
})

for (const n of [2, 3]) {
  test(`doubles ${n}`, () => {
    assert.strictEqual(n * 2, n + n)
  })
}

test('throws', () => {
  throw new TypeError('boom')
})
