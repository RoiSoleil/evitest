describe('with a broken hook', () => {
  before(() => {
    throw new Error('hook failed')
  })

  it('never runs', () => {})
})
