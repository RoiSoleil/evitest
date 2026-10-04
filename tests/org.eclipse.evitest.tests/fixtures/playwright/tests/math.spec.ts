import { expect, test } from '@playwright/test'

test.describe('math', () => {
  test.describe('adds', () => {
    test('one and one', () => {
      expect(1 + 1).toBe(2)
    })

    test('two and two', () => {
      expect(2 + 2).toBe(4)
    })
  })

  test.skip('skipped', () => {})
  test.fixme('later', () => {})

  test('compares objects', () => {
    expect({ a: 1, b: 2 }).toEqual({ a: 1, b: 3 })
  })
})

for (const n of [2, 3]) {
  test(`doubles ${n}`, () => {
    expect(n * 2).toBe(n + n)
  })
}

test('throws', () => {
  throw new TypeError('boom')
})
