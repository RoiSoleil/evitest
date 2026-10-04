import { beforeAll, describe, it } from 'vitest'

describe('with a broken hook', () => {
  beforeAll(() => {
    throw new Error('hook failed')
  })

  it('never runs', () => {})
})
