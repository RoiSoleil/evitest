function assertEquals(actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`Values are not equal: ${JSON.stringify(actual)} !== ${JSON.stringify(expected)}`)
  }
}

Deno.test('math', async (t) => {
  await t.step('adds', async (t) => {
    await t.step('one and one', () => {
      assertEquals(1 + 1, 2)
    })

    await t.step('two and two', () => {
      assertEquals(2 + 2, 4)
    })
  })

  await t.step({ name: 'skipped', ignore: true, fn: () => {} })

  await t.step('compares objects', () => {
    assertEquals({ a: 1, b: 2 }, { a: 1, b: 3 })
  })
})

for (const n of [2, 3]) {
  Deno.test(`doubles ${n}`, () => {
    assertEquals(n * 2, n + n)
  })
}

Deno.test.ignore('later', () => {})

Deno.test({ name: 'throws', fn: () => {
  throw new TypeError('boom')
} })
