'use strict'

// The reporter of Playwright Test with the objects of its documented reporter API (Suite, TestCase, TestResult).

const assert = require('node:assert/strict')
const { test } = require('node:test')
const { listen, summary } = require('./server.cjs')

function testCase(title, line, extra = {}) {
  return { type: 'test', title, location: { file: '/p/tests/a.spec.ts', line, column: 5 }, expectedStatus: 'passed',
    retries: 0, ...extra, outcome: () => extra.outcome ?? 'expected' }
}

function suite(type, title, children, location) {
  const suites = children.filter(child => child.type !== 'test')
  const tests = children.filter(child => child.type === 'test')
  return { type, title, location, suites, tests, entries: () => children }
}

test('the tree of the projects, then the results', async () => {
  const server = await listen()
  delete require.cache[require.resolve('../evitest-playwright.cjs')]
  const Reporter = require('../evitest-playwright.cjs')
  const playwright = new Reporter()
  const adds = testCase('adds', 3)
  const flaky = testCase('flaky', 4, { retries: 1 })
  const skipped = testCase('skipped', 5, { expectedStatus: 'skipped', outcome: 'skipped' })
  const fails = testCase('fails', 6, { outcome: 'unexpected' })
  const location = { file: '/p/tests/a.spec.ts', line: 0, column: 0 }
  const root = suite('root', '', [suite('project', 'chromium', [suite('file', 'a.spec.ts', [
    suite('describe', 'math', [adds, flaky, skipped, fails], { ...location, line: 2 })], location)])])
  assert.equal(playwright.printsToStdio(), false)
  playwright.onBegin({}, root)
  playwright.onTestBegin(adds)
  playwright.onTestEnd(adds, { status: 'passed', duration: 12, retry: 0, errors: [] })
  // A retried test: the last result counts.
  playwright.onTestEnd(flaky, { status: 'failed', duration: 1, retry: 0, errors: [{ message: 'first' }] })
  playwright.onTestEnd(flaky, { status: 'passed', duration: 1, retry: 1, errors: [] })
  playwright.onTestEnd(skipped, { status: 'skipped', duration: 0, retry: 0, errors: [] })
  playwright.onTestEnd(fails, { status: 'failed', duration: 3, retry: 0, errors: [{
    message: '\u001b[2mexpect(\u001b[22mreceived).toBe(expected)', stack: 'Error: expect\n    at /p/tests/a.spec.ts:7:9' }] })
  playwright.onError({ message: 'SyntaxError: broken.spec.ts', stack: 'at broken.spec.ts:1' })
  await playwright.onEnd({ status: 'failed' })
  await server.close()
  assert.equal(server.events.find(event => event.type === 'module').project, 'chromium')
  assert.deepEqual(summary(server.events), ['suite math', 'test math > adds', 'test math > flaky',
    'test math > skipped (skip)', 'test math > fails', 'passed math > adds', 'passed math > flaky',
    'skipped math > skipped', 'failed math > fails'])
  const failed = server.events.find(event => event.type === 'testEnd' && event.state === 'failed')
  assert.equal(failed.errors[0].message, 'expect(received).toBe(expected)')
  assert.equal(failed.errors[0].assertion, true)
  assert.equal(server.events.at(-1).errors[0].message, 'SyntaxError: broken.spec.ts')
})

test('a suite of an older Playwright, without entries()', async () => {
  const server = await listen()
  delete require.cache[require.resolve('../evitest-playwright.cjs')]
  const Reporter = require('../evitest-playwright.cjs')
  const playwright = new Reporter()
  const only = testCase('only', 3)
  const file = { type: 'file', title: 'a.spec.ts', location: { file: '/p/tests/a.spec.ts' }, suites: [], tests: [only] }
  playwright.onBegin({}, { suites: [{ title: '', suites: [file], tests: [] }] })
  await playwright.onEnd({ status: 'interrupted' })
  await server.close()
  // Not run: skipped when the run ends, interrupted.
  assert.deepEqual(summary(server.events), ['test only', 'skipped only'])
  assert.equal(server.events.at(-1).reason, 'interrupted')
})
