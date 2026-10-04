'use strict'

// The reporter of Jasmine with the objects of its documented Reporter interface.

const assert = require('node:assert/strict')
const { test } = require('node:test')
const { listen, summary } = require('./server.cjs')

const FILE = '/p/spec/mathSpec.js'

function reporter() {
  delete require.cache[require.resolve('../evitest-jasmine.cjs')]
  const Reporter = require('../evitest-jasmine.cjs')
  return new Reporter()
}

const spec = (id, description, parentSuiteId, status, extra = {}) => ({ id, description, parentSuiteId, status,
  filename: FILE, failedExpectations: [], duration: 2, ...extra })

test('the suites and the specs', async () => {
  const server = await listen()
  const jasmine = reporter()
  jasmine.jasmineStarted({ totalSpecsDefined: 4 })
  jasmine.suiteStarted({ id: 'suite1', description: 'math', parentSuiteId: null, filename: FILE })
  jasmine.specStarted(spec('spec0', 'adds', 'suite1'))
  jasmine.specDone(spec('spec0', 'adds', 'suite1', 'passed'))
  jasmine.specDone(spec('spec1', 'compares', 'suite1', 'failed', { failedExpectations: [{ matcherName: 'toEqual',
    message: 'Expected $.b = 2 to equal 3.', stack: 'Error: x\n    at <Jasmine>\n    at UserContext.<anonymous> (/p/spec/mathSpec.js:9:5)' }] }))
  jasmine.specDone(spec('spec2', 'skipped', 'suite1', 'pending'))
  jasmine.specDone(spec('spec3', 'never runs', 'suite1', 'failed', { failedExpectations: [{ matcherName: '',
    message: 'Not run because a beforeAll function failed. The beforeAll failure will be reported on the suite that caused it.' }] }))
  jasmine.suiteDone({ id: 'suite1', failedExpectations: [{ message: 'Error: hook failed', stack: 'Error: hook failed' }] })
  // Without the filename of Jasmine 5: not shown.
  jasmine.specDone({ id: 'spec4', description: 'old', status: 'passed', failedExpectations: [] })
  await jasmine.jasmineDone({ overallStatus: 'failed', failedExpectations: [] })
  await server.close()
  assert.deepEqual(summary(server.events), ['suite math', 'test math > adds', 'passed math > adds',
    'test math > compares', 'failed math > compares', 'test math > skipped (skip)', 'skipped math > skipped',
    'test math > never runs', 'skipped math > never runs', 'error math: hook failed'])
  const failed = server.events.find(event => event.type === 'testEnd' && event.state === 'failed')
  assert.equal(failed.errors[0].assertion, true)
  assert.match(failed.errors[0].trace, /mathSpec\.js:9:5/)
  assert.doesNotMatch(failed.errors[0].trace, /<Jasmine>/)
})

test('the empty values of the errors which are not comparisons (Jasmine 5)', async () => {
  const server = await listen()
  const jasmine = reporter()
  jasmine.jasmineStarted({})
  jasmine.specDone(spec('spec0', 'throws', null, 'failed', { failedExpectations: [{ matcherName: '',
    message: 'TypeError: boom', expected: '', actual: '', stack: '    at UserContext.<anonymous> (/p/spec/mathSpec.js:27:9)' }] }))
  await jasmine.jasmineDone({ overallStatus: 'failed' })
  await server.close()
  const failed = server.events.find(event => event.type === 'testEnd')
  assert.equal(failed.errors[0].name, 'TypeError')
  assert.equal(failed.errors[0].assertion, false)
  assert.equal(failed.errors[0].expected, undefined)
})

test('the specs excluded by --filter are not skipped by the user', async () => {
  const server = await listen()
  process.env.EVITEST_PATTERN = '^math adds$'
  const jasmine = reporter()
  jasmine.jasmineStarted({})
  jasmine.specDone(spec('spec0', 'other', null, 'excluded'))
  await jasmine.jasmineDone({ overallStatus: 'passed' })
  await server.close()
  delete process.env.EVITEST_PATTERN
  assert.deepEqual(summary(server.events), ['test other', 'skipped other'])
})

test('loaded as a helper, the reporter is added to the environment of Jasmine', () => {
  const added = []
  global.jasmine = { getEnv: () => ({ addReporter: reporterToAdd => added.push(reporterToAdd) }) }
  try {
    delete process.env.EVITEST_PORT
    reporter()
    assert.equal(added.length, 1)
    assert.equal(typeof added[0].specDone, 'function')
  }
  finally {
    delete global.jasmine
  }
})
