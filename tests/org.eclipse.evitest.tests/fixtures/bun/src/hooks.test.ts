import { beforeAll, describe, it } from 'bun:test'

describe('with a broken hook', () => {
  beforeAll(() => {
    throw new Error('hook failed')
  })

  it('never runs', () => {})
})
