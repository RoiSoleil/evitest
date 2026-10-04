import { test } from '@playwright/test'

test.describe('with a broken hook', () => {
  test.beforeAll(() => {
    throw new Error('hook failed')
  })

  test('never runs', () => {})
})
