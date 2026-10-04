import { before, describe, it } from 'node:test'

describe('with a broken hook', () => {
  before(() => {
    throw new Error('hook failed')
  })

  it('never runs', () => {})
})
