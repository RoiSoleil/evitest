'use strict'

// Shared by the reporters of EVitest for Jest, Mocha, Jasmine, node:test and Playwright Test: the connection to
// Eclipse and the events of the protocol, the same as the ones of the Vitest reporter (evitest-reporter.mjs).
//
// Eclipse starts the test runner with the port of its listening socket in EVITEST_PORT. Every event is one line of
// JSON. Without EVITEST_PORT the reporters send nothing, so they are harmless anywhere else.
//
// The frameworks which do not give the tree of the tests before running them add the tests with "node" events,
// under their file (a "module") or their suite.

const net = require('node:net')
const path = require('node:path')

const PROTOCOL_VERSION = 2

/** Removes the ANSI escape sequences (colors) of a text. */
function stripAnsi(text) {
  return typeof text === 'string' ? text.replace(/\u001b\[[0-9;?]*[ -/]*[@-~]/g, '') : text
}

function toPosix(file) {
  return file.split(path.sep).join('/')
}

/** Converts a value which may not be a string (expected and actual of some errors) to a string. */
function toText(value) {
  if (value === undefined) {
    return undefined
  }
  if (typeof value === 'string') {
    return stripAnsi(value)
  }
  try {
    const text = JSON.stringify(value, null, 2)
    return text === undefined ? String(value) : text
  }
  catch {
    return String(value)
  }
}

/** The stack trace of an error: its message, then the frames of its stack. */
function formatTrace(error) {
  const name = error.name || 'Error'
  const message = stripAnsi(error.message ?? '')
  let trace = message.startsWith(name) ? message : `${name}: ${message}`
  if (typeof error.stack === 'string') {
    // The stack starts with the message: only its frames are kept.
    const frames = stripAnsi(error.stack).split('\n').filter(line => /^\s+at\s/.test(line))
    if (frames.length > 0) {
      trace += `\n${frames.join('\n')}`
    }
  }
  if (error.cause && typeof error.cause === 'object' && error.cause !== error) {
    trace += `\nCaused by: ${formatTrace(error.cause)}`
  }
  return trace
}

/**
 * Serializes an error. The expected and the actual values are kept when the error is a comparison (assert,
 * expect...).
 */
function serializeError(error) {
  if (!error || typeof error !== 'object') {
    const message = stripAnsi(String(error))
    return { name: 'Error', message, trace: message.startsWith('Error') ? message : `Error: ${message}` }
  }
  const comparison = error.showDiff !== false && 'expected' in error && 'actual' in error
    && !(error.expected === undefined && error.actual === undefined)
  const expected = comparison ? toText(error.expected) : undefined
  const actual = comparison ? toText(error.actual) : undefined
  return {
    name: error.name,
    message: stripAnsi(error.message ?? ''),
    trace: typeof error.trace === 'string' ? stripAnsi(error.trace) : formatTrace(error),
    expected,
    actual,
    assertion: comparison || /Assert|Expect/.test(error.name ?? '') || error.code === 'ERR_ASSERTION'
      || error.matcherResult !== undefined,
  }
}

function serializeErrors(errors) {
  return Array.isArray(errors) && errors.length > 0 ? errors.map(serializeError) : undefined
}

/**
 * The connection to Eclipse, opened with the first event: Eclipse takes one connection per run, and a reporter which
 * sends nothing does not take it. The lines written before the connection is established are kept and sent with it.
 */
class Channel {
  constructor(port) {
    this.port = port
    this.closed = false
    this.socket = undefined
  }

  connect() {
    this.socket = net.createConnection({ host: '127.0.0.1', port: this.port })
    this.socket.setNoDelay(true)
    this.socket.on('error', (error) => {
      if (!this.closed) {
        this.closed = true
        process.stderr.write(`[EVitest] Lost the connection with Eclipse: ${error.message}\n`)
      }
    })
  }

  send(event) {
    if (this.closed) {
      return
    }
    if (!this.socket) {
      this.connect()
    }
    this.socket.write(`${JSON.stringify(event)}\n`)
  }

  close() {
    if (this.closed || !this.socket) {
      this.closed = true
      return Promise.resolve()
    }
    this.closed = true
    return new Promise(resolve => this.socket.end(resolve))
  }
}

/** The regular expression of the tests selected by Eclipse (EVITEST_PATTERN), null if all the tests run. */
function selectedPattern() {
  const pattern = process.env.EVITEST_PATTERN
  if (!pattern) {
    return null
  }
  try {
    return new RegExp(pattern)
  }
  catch {
    return null
  }
}

/**
 * A run: the tests sent to Eclipse, identified by their file, their project and their names.
 */
class Run {
  /**
   * @param {string} framework the name of the framework
   * @param {string} [version] its version
   * @param {string} [root] the folder of the run, process.cwd() by default
   */
  constructor(framework, version, root) {
    const port = Number.parseInt(process.env.EVITEST_PORT ?? '', 10)
    this.channel = Number.isInteger(port) && port > 0 ? new Channel(port) : undefined
    this.framework = framework
    this.version = version
    this.root = root ?? process.cwd()
    this.startTime = Date.now()
    this.started = false
    this.ended = false
    this.nodes = new Map()
    this.testsStarted = new Set()
    this.testsEnded = new Set()
    this.failedSuites = new Set()
  }

  send(event) {
    this.channel?.send(event)
  }

  /** Says hello and starts the run, once. */
  start() {
    if (this.started) {
      return
    }
    this.started = true
    globalThis.__evitestStarted = true
    this.startTime = Date.now()
    this.send({
      type: 'hello',
      protocol: PROTOCOL_VERSION,
      framework: this.framework,
      version: this.version,
      root: toPosix(this.root),
    })
    this.send({ type: 'runStart' })
  }

  relativeFile(file) {
    return toPosix(path.relative(this.root, file))
  }

  /** The identifier of the module (test file) of a file, sent to Eclipse the first time. */
  module(file, project) {
    this.start()
    const absolute = path.resolve(this.root, file)
    const id = `${project ? `${project}\u0001` : ''}${toPosix(absolute)}`
    if (!this.nodes.has(id)) {
      this.nodes.set(id, { kind: 'module', file: absolute, project })
      this.send({
        type: 'module',
        id,
        file: toPosix(absolute),
        name: this.relativeFile(absolute),
        project: project || undefined,
        children: [],
      })
    }
    return id
  }

  /**
   * The identifier of a suite of a file, sent to Eclipse with its parent suites the first time.
   *
   * @param {string} file the file of the suite
   * @param {string[]} names the names of the parent suites, then the name of the suite
   * @param {object} [options] line, column, mode, project
   */
  suite(file, names, options = {}) {
    const moduleId = this.module(file, options.project)
    if (names.length === 0) {
      return moduleId
    }
    const parent = this.suite(file, names.slice(0, -1), { project: options.project })
    const id = `${moduleId}\u0000s\u0000${names.join('\u0000')}`
    if (!this.nodes.has(id)) {
      this.nodes.set(id, { kind: 'suite' })
      this.sendNode(parent, id, 'suite', names, options)
    }
    return id
  }

  /**
   * The identifier of a test of a file, sent to Eclipse with its suites the first time. A test with the names of an
   * ended test is another test (two tests with the same name).
   *
   * @param {string} file the file of the test
   * @param {string[]} names the names of the suites, then the name of the test
   * @param {object} [options] line, column, mode (skip, todo), project, fresh (true for a new result)
   */
  test(file, names, options = {}) {
    const parent = this.suite(file, names.slice(0, -1), { project: options.project })
    const moduleId = this.module(file, options.project)
    const base = `${moduleId}\u0000t\u0000${names.join('\u0000')}`
    let id = base
    for (let index = 2; options.fresh && this.testsEnded.has(id); index++) {
      id = `${base}\u0000${index}`
    }
    if (!this.nodes.has(id)) {
      this.nodes.set(id, { kind: 'test' })
      this.sendNode(parent, id, 'test', names, options)
    }
    return id
  }

  sendNode(parent, id, kind, names, options) {
    this.send({
      type: 'node',
      parent,
      node: {
        id,
        kind,
        name: names[names.length - 1],
        names,
        mode: options.mode,
        line: typeof options.line === 'number' ? options.line : undefined,
        column: typeof options.column === 'number' ? options.column : undefined,
      },
    })
  }

  testStart(id) {
    if (!this.testsStarted.has(id)) {
      this.testsStarted.add(id)
      this.send({ type: 'testStart', id })
    }
  }

  /**
   * Ends a test.
   *
   * @param {string} id the identifier of the test
   * @param {'passed'|'failed'|'skipped'} state its result
   * @param {number} [duration] in milliseconds
   * @param {unknown[]} [errors] the errors of a failed test
   */
  testEnd(id, state, duration, errors) {
    if (this.testsEnded.has(id)) {
      return
    }
    this.testsEnded.add(id)
    this.testsStarted.add(id)
    this.send({
      type: 'testEnd',
      id,
      state: state === 'passed' || state === 'failed' ? state : 'skipped',
      duration: typeof duration === 'number' && Number.isFinite(duration) ? Math.round(duration) : undefined,
      errors: serializeErrors(errors),
    })
  }

  /** The errors of a suite or of a module outside its tests (a hook, a file which cannot be loaded). */
  suiteError(id, errors, duration) {
    const serialized = serializeErrors(errors)
    if (serialized && !this.failedSuites.has(id)) {
      this.failedSuites.add(id)
      this.send({
        type: 'suiteError',
        id,
        errors: serialized,
        duration: typeof duration === 'number' ? Math.round(duration) : undefined,
      })
    }
  }

  /** Ends the run with the errors outside the tests, and closes the connection. */
  async end(errors, reason) {
    if (this.ended) {
      return
    }
    this.start()
    this.ended = true
    this.send({
      type: 'runEnd',
      reason,
      duration: Date.now() - this.startTime,
      errors: serializeErrors(errors),
    })
    await this.channel?.close()
  }
}

/** The version of a package installed for the folder (or one of its parents), undefined if it is not known. */
function packageVersion(name, folder) {
  const fs = require('node:fs')
  for (let current = path.resolve(folder ?? process.cwd()); ; current = path.dirname(current)) {
    // Read from node_modules: the "exports" of some packages hide their package.json from require.resolve.
    const file = path.join(current, 'node_modules', name, 'package.json')
    try {
      return JSON.parse(fs.readFileSync(file, 'utf8')).version
    }
    catch {
      // The parent folder.
    }
    if (path.dirname(current) === current) {
      return undefined
    }
  }
}

/**
 * Reports the crash of a framework which fails before its reporter starts (a test file which cannot be loaded makes
 * Mocha and Jasmine exit at once): the output of the process is kept, and sent to Eclipse as the error of the run when
 * the process exits without a run.
 */
function reportCrashes(framework, version) {
  if (!process.env.EVITEST_PORT || globalThis.__evitestCrashes) {
    return
  }
  globalThis.__evitestCrashes = true
  let output = ''
  for (const stream of [process.stdout, process.stderr]) {
    const write = stream.write.bind(stream)
    stream.write = (chunk, ...rest) => {
      if (output.length < 100_000) {
        output += typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString('utf8')
      }
      return write(chunk, ...rest)
    }
  }
  let reported = false
  const report = () => {
    if (reported || globalThis.__evitestStarted) {
      return Promise.resolve()
    }
    reported = true
    const text = stripAnsi(output).trim()
    const line = text.split('\n').find(candidate => /\w*(Error|Exception)\b/.test(candidate)) ?? text.split('\n')[0]
    const run = new Run(framework, version)
    return run.end([{
      name: 'Error',
      message: line || `${framework} ended before running the tests.`,
      trace: text || `${framework} ended before running the tests.`,
    }])
  }
  const exit = process.exit.bind(process)
  process.exit = (code) => {
    if (reported || globalThis.__evitestStarted) {
      exit(code)
      return
    }
    report().finally(() => exit(code))
  }
  process.once('beforeExit', () => {
    report()
  })
}

module.exports = {
  reportCrashes,
  PROTOCOL_VERSION,
  Run,
  packageVersion,
  selectedPattern,
  serializeError,
  stripAnsi,
  toPosix,
  toText,
}
