'use strict'

// The reporter of Jest with the objects of its documented reporter API (@jest/reporters, @jest/test-result).

const assert = require('node:assert/strict')
const { test } = require('node:test')
const { listen, summary } = require('./server.cjs')

const FILE = '/p/src/math.test.js'

function result(ancestorTitles, title, status, extra = {}) {
  return { ancestorTitles, title, fullName: [...ancestorTitles, title].join(' '), status, duration: 3,
    failureMessages: [], failureDetails: [], location: { line: 5, column: 3 }, ...extra }
}

function reporter() {
  delete require.cache[require.resolve('../evitest-jest.cjs')]
  const Reporter = require('../evitest-jest.cjs')
  return new Reporter({ rootDir: '/p' }, {}, {})
}

test('the tests of a file, while they run and when the file ends', async () => {
  const server = await listen()
  process.env.EVITEST_PATTERN = '^math(?: | > )'
  const jest = reporter()
  const testFile = { path: FILE, context: { config: {} } }
  jest.onRunStart({ numTotalTestSuites: 1 }, {})
  jest.onTestFileStart(testFile)
  jest.onTestCaseStart(testFile, { ancestorTitles: ['math'], title: 'adds', fullName: 'math adds' })
  const adds = result(['math'], 'adds', 'passed')
  jest.onTestCaseResult(testFile, adds)
  const compares = result(['math'], 'compares', 'failed', {
    failureMessages: ['Error: expect(received).toEqual(expected)\n\n- 3\n+ 2\n    at Object.<anonymous> (/p/src/math.test.js:9:5)'],
    failureDetails: [{ matcherResult: { expected: { b: 3 }, actual: { b: 2 }, pass: false } }],
  })
  jest.onTestCaseResult(testFile, compares)
  // The file result repeats the results, with the skipped ones; a copy is the same result.
  jest.onTestFileResult(testFile, {
    testResults: [{ ...adds }, compares, result(['math'], 'skipped', 'pending'), result([], 'other', 'pending'),
      result(['math'], 'later', 'todo')],
  })
  await jest.onRunComplete(new Set(), { wasInterrupted: false })
  await server.close()
  delete process.env.EVITEST_PATTERN
  assert.deepEqual(summary(server.events), [
    'suite math', 'test math > adds', 'passed math > adds', 'test math > compares', 'failed math > compares',
    'test math > skipped (skip)', 'skipped math > skipped',
    // Not selected by the pattern: not skipped by the user.
    'test other', 'skipped other',
    'test math > later (todo)', 'skipped math > later'])
  const failed = server.events.find(event => event.type === 'testEnd' && event.state === 'failed')
  assert.equal(failed.errors[0].assertion, true)
  assert.equal(failed.errors[0].expected, '{\n  "b": 3\n}')
  assert.match(failed.errors[0].trace, /math\.test\.js:9:5/)
})

test('a file which cannot run, and a Jest without onTestCaseStart', async () => {
  const server = await listen()
  const jest = reporter()
  const testFile = { path: FILE, context: { config: { displayName: { name: 'unit', color: 'blue' } } } }
  jest.onTestFileStart(testFile)
  jest.onTestFileResult(testFile, {
    testResults: [result([], 'only at the end', 'passed')],
    testExecError: { message: 'Jest encountered an unexpected token', stack: 'SyntaxError' },
    failureMessage: '  ● Test suite failed to run\n\n    Jest encountered an unexpected token',
  })
  await jest.onRunComplete(new Set(), { runExecError: { message: 'global failure' } })
  await server.close()
  const module = server.events.find(event => event.type === 'module')
  assert.equal(module.project, 'unit')
  assert.deepEqual(summary(server.events), ['test only at the end', 'passed only at the end',
    'error src/math.test.js: Jest encountered an unexpected token'])
  assert.equal(server.events.at(-1).errors[0].message, 'global failure')
})

test('an unexpected object of a new version of Jest does not throw', async () => {
  const server = await listen()
  const jest = reporter()
  assert.doesNotThrow(() => jest.onTestCaseResult(undefined, undefined))
  assert.doesNotThrow(() => jest.onTestFileResult({ path: FILE }, {}))
  await jest.onRunComplete()
  await server.close()
  assert.equal(server.events.at(-1).type, 'runEnd')
})
