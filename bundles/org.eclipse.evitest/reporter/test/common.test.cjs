'use strict'

const assert = require('node:assert/strict')
const { spawn } = require('node:child_process')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { test } = require('node:test')
const common = require('../evitest-common.cjs')
const { listen, ofType } = require('./server.cjs')

test('a run sends the protocol of EVitest: hello, the tests, their results, the end', async () => {
  const server = await listen()
  const run = new common.Run('Test', '1.2.3', '/p')
  const testId = run.test('/p/src/a.test.js', ['math', 'adds'], { line: 3, column: 5 })
  run.testStart(testId)
  run.testEnd(testId, 'passed', 1.6)
  run.testEnd(testId, 'failed')
  const suiteId = run.suite('/p/src/a.test.js', ['math'])
  run.suiteError(suiteId, [new Error('hook failed')], 2)
  await run.end([new Error('late')], 'interrupted')
  await server.close()
  const types = server.events.map(event => event.type)
  assert.deepEqual(types, ['hello', 'runStart', 'module', 'node', 'node', 'testStart', 'testEnd', 'suiteError',
    'runEnd'])
  assert.deepEqual(server.events[0], { type: 'hello', protocol: 2, framework: 'Test', version: '1.2.3', root: '/p' })
  const module = ofType(server.events, 'module')[0]
  assert.equal(module.file, '/p/src/a.test.js')
  assert.equal(module.name, 'src/a.test.js')
  const [suite, node] = ofType(server.events, 'node')
  assert.equal(suite.parent, module.id)
  assert.equal(suite.node.kind, 'suite')
  assert.deepEqual(suite.node.names, ['math'])
  assert.equal(node.parent, suite.node.id)
  assert.deepEqual(node.node, { id: testId, kind: 'test', name: 'adds', names: ['math', 'adds'], line: 3, column: 5 })
  // One end per test, the first one, the duration rounded.
  assert.deepEqual(ofType(server.events, 'testEnd'), [{ type: 'testEnd', id: testId, state: 'passed', duration: 2 }])
  assert.equal(ofType(server.events, 'suiteError')[0].errors[0].message, 'hook failed')
  const end = ofType(server.events, 'runEnd')[0]
  assert.equal(end.reason, 'interrupted')
  assert.equal(end.errors[0].message, 'late')
})

test('the tests of the same file and names are the same test, unless it ended and the result is a new one', () => {
  delete process.env.EVITEST_PORT
  const run = new common.Run('Test', undefined, '/p')
  const first = run.test('/p/a.test.js', ['same'])
  assert.equal(run.test('/p/a.test.js', ['same']), first)
  run.testEnd(first, 'passed')
  const second = run.test('/p/a.test.js', ['same'], { fresh: true })
  assert.notEqual(second, first)
  // A test and a suite with the same names are different nodes, and the projects are different modules.
  assert.notEqual(run.suite('/p/a.test.js', ['same']), first)
  assert.notEqual(run.module('/p/a.test.js', 'unit'), run.module('/p/a.test.js'))
})

test('without EVITEST_PORT nothing is sent, and a reporter which sends nothing does not connect', async () => {
  delete process.env.EVITEST_PORT
  await new common.Run('Test').end()
  const server = await listen()
  // Eclipse takes one connection per run: an idle reporter must not take it.
  const idle = new common.Run('Idle')
  assert.ok(idle)
  const run = new common.Run('Test')
  await run.end()
  await server.close()
  assert.equal(server.connections(), 1)
  assert.deepEqual(server.events.map(event => event.type), ['hello', 'runStart', 'runEnd'])
})

test('the errors: assertions with their values, other errors, values which are not errors', () => {
  const assertion = new assert.AssertionError({ actual: { b: 2 }, expected: { b: 3 }, operator: 'deepStrictEqual' })
  const serialized = common.serializeError(assertion)
  assert.equal(serialized.name, 'AssertionError')
  assert.equal(serialized.assertion, true)
  assert.equal(serialized.expected, '{\n  "b": 3\n}')
  assert.equal(serialized.actual, '{\n  "b": 2\n}')
  assert.match(serialized.trace, /^AssertionError/)
  assert.match(serialized.trace, /\n\s+at /)

  const error = new TypeError('boom', { cause: new Error('the cause') })
  const typeError = common.serializeError(error)
  assert.equal(typeError.assertion, false)
  assert.equal(typeError.expected, undefined)
  assert.match(typeError.trace, /^TypeError: boom/)
  assert.match(typeError.trace, /Caused by: Error: the cause/)

  // Colors are removed, a trace of the framework is kept, values without a diff are not compared.
  assert.equal(common.serializeError({ name: 'Error', message: '\u001b[31mred\u001b[39m' }).message, 'red')
  assert.equal(common.serializeError({ message: 'm', trace: 'Error: m\n    at x' }).trace, 'Error: m\n    at x')
  assert.equal(common.serializeError({ message: 'm', expected: 1, actual: 2, showDiff: false }).expected, undefined)
  assert.deepEqual(common.serializeError('just a text'), { name: 'Error', message: 'just a text',
    trace: 'Error: just a text' })
  assert.equal(common.serializeError(undefined).message, 'undefined')
  // A circular value is not a reason to fail.
  const circular = { message: 'c', expected: {}, actual: 1 }
  circular.expected.self = circular.expected
  assert.equal(typeof common.serializeError(circular).expected, 'string')
})

test('the pattern of the selected tests', () => {
  delete process.env.EVITEST_PATTERN
  assert.equal(common.selectedPattern(), null)
  process.env.EVITEST_PATTERN = '^math(?: | > )adds$'
  assert.ok(common.selectedPattern().test('math adds'))
  process.env.EVITEST_PATTERN = '(unclosed'
  assert.equal(common.selectedPattern(), null)
  delete process.env.EVITEST_PATTERN
})

test('the version of a package whose exports hide its package.json', () => {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'evitest-version-'))
  try {
    const nested = path.join(folder, 'node_modules', '@scope', 'tool')
    fs.mkdirSync(nested, { recursive: true })
    fs.writeFileSync(path.join(nested, 'package.json'), JSON.stringify({ version: '4.5.6', exports: { '.': './x.js' } }))
    fs.mkdirSync(path.join(folder, 'deep', 'er'), { recursive: true })
    assert.equal(common.packageVersion('@scope/tool', path.join(folder, 'deep', 'er')), '4.5.6')
    assert.equal(common.packageVersion('missing', folder), undefined)
  }
  finally {
    fs.rmSync(folder, { recursive: true, force: true })
  }
})

test('a reporter which fails does not stop the tests', async () => {
  const messages = []
  const write = process.stderr.write
  process.stderr.write = (text) => {
    messages.push(String(text))
    return true
  }
  try {
    class Reporter {
      fails() {
        throw new Error('changed API')
      }

      async rejects() {
        throw new Error('async')
      }

      works(value) {
        return value * 2
      }
    }
    const reporter = common.guard(new Reporter(), 'Test')
    assert.equal(reporter.fails(), undefined)
    assert.equal(reporter.fails(), undefined)
    assert.equal(await reporter.rejects(), undefined)
    assert.equal(reporter.works(21), 42)
    const listener = common.safe('Test', 'listener', () => {
      throw new Error('listener')
    })
    assert.equal(listener(), undefined)
  }
  finally {
    process.stderr.write = write
  }
  // One message per method.
  assert.equal(messages.filter(message => message.includes('failed in fails')).length, 1)
  assert.ok(messages.some(message => message.includes('failed in rejects')))
  assert.ok(messages.some(message => message.includes('failed in listener')))
  assert.ok(messages.every(message => message.startsWith('[EVitest]')))
})

test('a framework which exits before its reporter starts sends its output as the error of the run', async () => {
  const server = await listen()
  const script = `
    const { reportCrashes } = require(${JSON.stringify(path.join(__dirname, '..', 'evitest-common.cjs'))})
    reportCrashes('Crash', '1.0.0')
    console.error('SyntaxError: Unexpected end of input')
    console.error('    at broken.test.js:3')
    process.exit(3)
  `
  const exitCode = await new Promise((resolve) => {
    const child = spawn(process.execPath, ['-e', script], {
      env: { ...process.env, EVITEST_PORT: String(server.port) },
      stdio: ['ignore', 'ignore', 'pipe'],
    })
    child.on('exit', resolve)
  })
  await server.close()
  // The exit code of the framework is kept.
  assert.equal(exitCode, 3)
  const end = ofType(server.events, 'runEnd')[0]
  assert.equal(server.events[0].framework, 'Crash')
  assert.equal(end.errors[0].message, 'SyntaxError: Unexpected end of input')
  assert.match(end.errors[0].trace, /broken\.test\.js:3/)
})

test('a framework whose reporter started does not send its crash', async () => {
  const server = await listen()
  const script = `
    const { Run, reportCrashes } = require(${JSON.stringify(path.join(__dirname, '..', 'evitest-common.cjs'))})
    reportCrashes('Crash')
    const run = new Run('Started')
    run.end().then(() => process.exit(1))
  `
  await new Promise((resolve) => {
    const child = spawn(process.execPath, ['-e', script], {
      env: { ...process.env, EVITEST_PORT: String(server.port) },
      stdio: 'ignore',
    })
    child.on('exit', resolve)
  })
  await server.close()
  assert.deepEqual(server.events.map(event => event.framework ?? event.type), ['Started', 'runStart', 'runEnd'])
  assert.equal(ofType(server.events, 'runEnd')[0].errors, undefined)
})
