import { describe, expect, it, test } from 'bun:test'

describe('math', () => {
  describe('adds', () => {
    it('one and one', () => {
      expect(1 + 1).toBe(2)
    })

    it('two and two', () => {
      expect(2 + 2).toBe(4)
    })
  })

  it.skip('skipped', () => {})
  it.todo('later')

  it('compares objects', () => {
    expect({ a: 1, b: 2 }).toEqual({ a: 1, b: 3 })
  })
})

test.each([2, 3])('doubles %i', (n) => {
  expect(n * 2).toBe(n + n)
})

test('throws', () => {
  throw new TypeError('boom')
})
