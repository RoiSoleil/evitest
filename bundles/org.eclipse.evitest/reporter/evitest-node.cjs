'use strict'

// EVitest reporter of the test runner of Node.js (node --test): streams the results to Eclipse (see
// evitest-common.cjs).
//
// The events test:start, test:pass and test:fail come in the order of the declarations of the tests, with their level
// of nesting: the names of the suites of a test are the names of the tests started before it at the lower levels. A
// test file which fails outside its tests (it cannot be loaded) is reported as a failed test named after the file,
// whose error is in the output of the file.

const path = require('node:path')
const { Run, stripAnsi } = require('./evitest-common.cjs')

/** What is known about the tests of a file while they are reported. */
class FileState {
  constructor(file) {
    this.file = file
    this.stack = []
    this.open = []
    this.output = ''
  }
}

/** True for the test standing for a test file (named after the file), not for a test of the file. */
function isFileTest(run, data) {
  if (data.nesting !== 0 || !data.file || data.line !== 1 || data.column !== 1) {
    return false
  }
  const file = path.resolve(run.root, data.file)
  return data.name === data.file || data.name === file || path.resolve(run.root, data.name) === file
}

/** The error of a failed test: the cause of the ERR_TEST_FAILURE of Node.js. */
function errorOf(details) {
  const error = details?.error
  if (!error) {
    return undefined
  }
  return error.cause && typeof error.cause === 'object' ? error.cause : error
}

module.exports = async function * evitestNodeReporter(source) {
  const run = new Run('node:test', process.versions.node, process.cwd())
  const files = new Map()
  const stateOf = (data) => {
    const file = path.resolve(run.root, data.file ?? 'unknown')
    let state = files.get(file)
    if (!state) {
      state = new FileState(file)
      files.set(file, state)
    }
    return state
  }
  const errors = []
  run.start()

  for await (const event of source) {
    const data = event.data ?? {}
    switch (event.type) {
      case 'test:stdout':
      case 'test:stderr':
        if (data.file) {
          const state = stateOf(data)
          if (state.output.length < 100_000) {
            state.output += data.message ?? ''
          }
        }
        break
      case 'test:start':
        if (data.file && !isFileTest(run, data)) {
          started(run, stateOf(data), data)
        }
        else if (data.file) {
          run.module(path.resolve(run.root, data.file))
        }
        break
      case 'test:pass':
      case 'test:fail':
        if (!data.file) {
          if (event.type === 'test:fail') {
            errors.push(errorOf(data.details) ?? { name: 'Error', message: data.name })
          }
        }
        else if (isFileTest(run, data)) {
          if (event.type === 'test:fail') {
            fileFailed(run, stateOf(data), data)
          }
        }
        else {
          ended(run, stateOf(data), data, event.type === 'test:pass')
        }
        break
      default:
        break
    }
  }
  await run.end(errors)
}

function started(run, state, data) {
  const nesting = data.nesting ?? 0
  const names = [...state.stack.slice(0, nesting), data.name]
  state.stack.length = nesting
  state.stack.push(data.name)
  // A test with subtests is a suite.
  const parent = state.open.findLast(entry => entry.nesting === nesting - 1 && !entry.ended)
  if (parent && !parent.emitted) {
    emit(run, state, parent, 'suite')
  }
  state.open.push({ nesting, name: data.name, names, line: data.line, column: data.column, emitted: false })
}

function emit(run, state, entry, kind, mode) {
  entry.emitted = true
  entry.kind = kind
  entry.id = kind === 'suite'
    ? run.suite(state.file, entry.names, { line: entry.line, column: entry.column })
    : run.test(state.file, entry.names, { line: entry.line, column: entry.column, mode })
}

function ended(run, state, data, passed) {
  const nesting = data.nesting ?? 0
  let entry = state.open.findLast(candidate => candidate.nesting === nesting && candidate.name === data.name
    && !candidate.ended)
  if (!entry) {
    // Reported without a start.
    entry = { nesting, name: data.name, names: [...state.stack.slice(0, nesting), data.name], line: data.line,
      column: data.column, emitted: false }
  }
  entry.ended = true
  state.open = state.open.filter(candidate => candidate !== entry)
  const details = data.details ?? {}
  const failureType = details.error?.failureType
  if (!entry.emitted) {
    const mode = data.skip ? 'skip' : data.todo ? 'todo' : undefined
    emit(run, state, entry, details.type === 'suite' ? 'suite' : 'test', mode)
  }
  if (entry.kind === 'suite') {
    // A suite fails because of its tests (already reported), of a hook, or of an error in its body.
    if (!passed && failureType !== 'subtestsFailed') {
      const error = errorOf(details)
      if (error) {
        run.suiteError(entry.id, [error], details.duration_ms)
      }
    }
    return
  }
  run.testStart(entry.id)
  if (data.skip || data.todo || failureType === 'cancelledByParent') {
    run.testEnd(entry.id, 'skipped', details.duration_ms)
  }
  else if (passed) {
    run.testEnd(entry.id, 'passed', details.duration_ms)
  }
  else {
    run.testEnd(entry.id, 'failed', details.duration_ms, [errorOf(details) ?? { name: 'Error', message: 'Failed' }])
  }
}

/** A test file which failed outside its tests: its error is in its output. */
function fileFailed(run, state, data) {
  const moduleId = run.module(state.file)
  const output = stripAnsi(state.output).trim()
  const cause = errorOf(data.details)
  const message = output.split('\n').find(line => /\w*(Error|Exception)\b/.test(line))
    ?? (cause && cause.message !== undefined && String(cause.message) !== 'undefined' ? String(cause.message) : 'The test file failed.')
  run.suiteError(moduleId, [{ name: 'Error', message, trace: output || message }], data.details?.duration_ms)
}
