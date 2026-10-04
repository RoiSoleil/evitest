const assert = require('node:assert')

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
  it('later')

  it('compares objects', () => {
    assert.deepStrictEqual({ a: 1, b: 2 }, { a: 1, b: 3 })
  })
})

for (const n of [2, 3]) {
  it(`doubles ${n}`, () => {
    assert.strictEqual(n * 2, n + n)
  })
}

it('throws', () => {
  throw new TypeError('boom')
})
