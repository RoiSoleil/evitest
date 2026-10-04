describe('math', () => {
  describe('adds', () => {
    it('one and one', () => {
      expect(1 + 1).toBe(2)
    })

    it('two and two', () => {
      expect(2 + 2).toBe(4)
    })
  })

  xit('skipped', () => {})
  it('later')

  it('compares objects', () => {
    expect({ a: 1, b: 2 }).toEqual({ a: 1, b: 3 })
  })
})

for (const n of [2, 3]) {
  it(`doubles ${n}`, () => {
    expect(n * 2).toBe(n + n)
  })
}

it('throws', () => {
  throw new TypeError('boom')
})
