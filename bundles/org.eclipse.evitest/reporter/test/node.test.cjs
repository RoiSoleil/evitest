'use strict'

// The reporter of node:test with the documented events of the custom reporters, as Node.js 22 sends them.

const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { listen, summary } = require('./server.cjs')

async function report(events) {
  const server = await listen()
  const reporter = require('../evitest-node.cjs')
  async function * source() {
    yield * events
  }
  for await (const line of reporter(source())) {
    assert.fail(`unexpected output ${line}`)
  }
  await server.close()
  return server.events
}

const file = path.resolve('test/math.test.js')
const event = (type, name, nesting, extra = {}) => ({ type, data: { name, nesting, file, line: 1 + nesting,
  column: 1, ...extra } })

test('the tests in the order of their declarations, the suites found from their tests', async () => {
  const events = await report([
    event('test:enqueue', 'math', 0),
    event('test:start', 'math', 0),
    event('test:start', 'adds', 1),
    event('test:pass', 'adds', 1, { details: { type: 'test', duration_ms: 1.4 } }),
    event('test:start', 'skipped', 1, { skip: true }),
    event('test:pass', 'skipped', 1, { skip: true, details: { type: 'test' } }),
    event('test:start', 'later', 1),
    event('test:pass', 'later', 1, { todo: true, details: { type: 'test' } }),
    event('test:start', 'compares', 1),
    event('test:fail', 'compares', 1, { details: { type: 'test', error: { code: 'ERR_TEST_FAILURE',
      failureType: 'testCodeFailure', cause: { name: 'AssertionError', message: 'not equal', code: 'ERR_ASSERTION',
        expected: 3, actual: 2, stack: 'AssertionError: not equal\n    at file:///x.js:3:4' } } } }),
    event('test:fail', 'math', 0, { details: { type: 'suite', error: { failureType: 'subtestsFailed' } } }),
    event('test:diagnostic', undefined, 0, { message: 'tests 3' }),
    { type: 'test:summary', data: {} },
  ])
  assert.deepEqual(summary(events), ['suite math', 'test math > adds', 'passed math > adds',
    'test math > skipped (skip)', 'skipped math > skipped', 'test math > later (todo)', 'skipped math > later',
    'test math > compares', 'failed math > compares'])
  const failed = events.find(item => item.type === 'testEnd' && item.state === 'failed')
  assert.equal(failed.errors[0].assertion, true)
  assert.equal(failed.errors[0].expected, '3')
})

test('a failed hook fails its suite and cancels its tests', async () => {
  const events = await report([
    event('test:start', 'with a broken hook', 0),
    event('test:start', 'never runs', 1),
    event('test:fail', 'never runs', 1, { details: { type: 'test', error: { failureType: 'cancelledByParent' } } }),
    event('test:fail', 'with a broken hook', 0, { details: { type: 'suite', error: { failureType: 'hookFailed',
      cause: { name: 'Error', message: 'hook failed', stack: 'Error: hook failed' } } } }),
  ])
  assert.deepEqual(summary(events), ['suite with a broken hook', 'test with a broken hook > never runs',
    'skipped with a broken hook > never runs', 'error with a broken hook: hook failed'])
})

test('a test file which cannot be loaded: its error is in its output', async () => {
  const relative = path.relative(process.cwd(), file)
  const fileTest = (type, extra = {}) => ({ type, data: { name: relative, nesting: 0, file: relative, line: 1,
    column: 1, ...extra } })
  const events = await report([
    { type: 'test:stderr', data: { file: relative, message: 'file:///x/broken.test.js:5\n\nSyntaxError: Unexpected end of input\n' } },
    fileTest('test:start'),
    fileTest('test:fail', { details: { type: 'test', error: { failureType: 'testCodeFailure' } } }),
  ])
  assert.deepEqual(summary(events), [`error ${relative}: SyntaxError: Unexpected end of input`])
})

test('the unknown events and the broken data of a new version of Node.js do not stop the run', async () => {
  const messages = []
  const write = process.stderr.write
  process.stderr.write = (text) => {
    messages.push(String(text))
    return true
  }
  let events
  try {
    events = await report([
      { type: 'test:new-event', data: { anything: true } },
      { type: 'test:pass' },
      { type: 'test:fail', data: { name: 'outside a file', details: { error: { message: 'global' } } } },
      { type: 'test:start', data: null },
    ])
  }
  finally {
    process.stderr.write = write
  }
  assert.equal(events.at(-1).type, 'runEnd')
  assert.equal(events.at(-1).errors[0].message, 'global')
  assert.ok(messages.every(message => message.startsWith('[EVitest]')), messages.join(''))
})
