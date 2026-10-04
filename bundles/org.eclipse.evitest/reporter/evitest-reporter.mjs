// EVitest reporter: streams the events of a Vitest run to Eclipse.
//
// Eclipse starts Vitest with this file as a custom reporter and the port of its listening socket in EVITEST_PORT.
// Every event is one line of JSON. Without EVITEST_PORT the reporter does nothing, so it is harmless anywhere else.
//
// Uses the documented reporter API of Vitest (https://vitest.dev/advanced/reporters).
// Vitest 3 and newer use the reported tasks API (onTestRunStart, onTestModuleCollected, onTestCaseResult...).
// Vitest 1 and 2 only have the task API (onCollected, onTaskUpdate, onFinished): it is used when onTestRunStart is
// never called. Vitest 3 calls both, the legacy hooks are then ignored.

import net from 'node:net'
import path from 'node:path'
import common from './evitest-common.cjs'

const PROTOCOL_VERSION = 1

/** Removes the ANSI escape sequences (colors) of a text. */
function stripAnsi(text) {
  return typeof text === 'string' ? text.replace(/\u001b\[[0-9;?]*[ -/]*[@-~]/g, '') : text
}

function toPosix(file) {
  return file.split(path.sep).join('/')
}

/** Converts a value which may not be a string (expected and actual of some errors) to a string. */
function toText(value) {
  if (value === undefined || value === null) {
    return undefined
  }
  if (typeof value === 'string') {
    return stripAnsi(value)
  }
  try {
    return JSON.stringify(value, null, 2)
  }
  catch {
    return String(value)
  }
}

/** Formats a serialized error of Vitest as a stack trace, with the source mapped frames when Vitest gives them. */
function formatTrace(error) {
  const name = error.name || error.nameStr || 'Error'
  const message = stripAnsi(error.message ?? '')
  let trace = message.startsWith(name) ? message : `${name}: ${message}`
  if (Array.isArray(error.stacks) && error.stacks.length > 0) {
    for (const frame of error.stacks) {
      const location = `${frame.file}:${frame.line}:${frame.column}`
      trace += frame.method ? `\n    at ${frame.method} (${location})` : `\n    at ${location}`
    }
  }
  else if (typeof error.stack === 'string') {
    // The stack starts with the message: only its frames are kept.
    const frames = stripAnsi(error.stack).split('\n').filter(line => /^\s+at\s/.test(line))
    if (frames.length > 0) {
      trace += `\n${frames.join('\n')}`
    }
  }
  if (error.cause && typeof error.cause === 'object') {
    trace += `\nCaused by: ${formatTrace(error.cause)}`
  }
  return trace
}

function serializeError(error) {
  if (!error || typeof error !== 'object') {
    return { name: 'Error', message: String(error), trace: `Error: ${String(error)}` }
  }
  let expected = toText(error.expected)
  let actual = toText(error.actual)
  if (error.showDiff === false || (expected === 'undefined' && actual === 'undefined')) {
    // Vitest 1 and 2 give "undefined" to the errors which are not comparisons.
    expected = undefined
    actual = undefined
  }
  const assertion = (expected !== undefined && actual !== undefined) || /Assertion/.test(error.name ?? '')
  return {
    name: error.name,
    message: stripAnsi(error.message ?? ''),
    trace: formatTrace(error),
    expected,
    actual,
    assertion,
  }
}

function serializeErrors(errors) {
  return Array.isArray(errors) && errors.length > 0 ? errors.map(serializeError) : undefined
}

/** The connection to Eclipse. The lines written before the connection is established are kept and sent with it. */
class Channel {
  constructor(port) {
    this.closed = false
    this.socket = net.createConnection({ host: '127.0.0.1', port })
    this.socket.setNoDelay(true)
    this.socket.on('error', (error) => {
      if (!this.closed) {
        this.closed = true
        process.stderr.write(`[EVitest] Lost the connection with Eclipse: ${error.message}\n`)
      }
    })
  }

  send(event) {
    if (!this.closed) {
      this.socket.write(`${JSON.stringify(event)}\n`)
    }
  }

  close() {
    if (this.closed) {
      return Promise.resolve()
    }
    this.closed = true
    return new Promise(resolve => this.socket.end(resolve))
  }
}

export default class EVitestReporter {
  constructor() {
    const port = Number.parseInt(process.env.EVITEST_PORT ?? '', 10)
    this.channel = Number.isInteger(port) && port > 0 ? new Channel(port) : undefined
    this.modern = false
    this.ended = false
    this.root = process.cwd()
    this.sentModules = new Set()
    this.started = new Set()
    this.finished = new Set()
    this.failedSuites = new Set()
    this.startTime = Date.now()
    // An error of EVitest (a change of the API of Vitest) does not stop the tests.
    return common.guard(this, 'Vitest')
  }

  send(event) {
    this.channel?.send(event)
  }

  onInit(vitest) {
    this.vitest = vitest
    this.root = vitest?.config?.root ?? this.root
    this.startTime = Date.now()
    // The locations of the tests open them in their editor (the --includeTaskLocation option of Vitest 3 and newer).
    for (const config of [vitest?.config, ...(vitest?.projects ?? []).map(project => project.config)]) {
      if (config && typeof config === 'object') {
        try {
          config.includeTaskLocation = true
        }
        catch {
          // A read only configuration: the editor falls back on the names of the tests.
        }
      }
    }
    // Vitest 1 has no version on the Vitest object: the one of its package.
    const version = vitest?.version ?? common.packageVersion('vitest', this.root)
    this.send({ type: 'hello', protocol: PROTOCOL_VERSION, framework: 'Vitest', version, vitest: version,
      root: toPosix(this.root) })
  }

  relativeFile(file) {
    return toPosix(path.relative(this.root, file))
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Vitest 3 and newer
  // ---------------------------------------------------------------------------------------------------------------

  onTestRunStart(specifications) {
    this.modern = true
    this.startTime = Date.now()
    this.send({ type: 'runStart', files: specifications?.length })
  }

  onTestModuleQueued(testModule) {
    this.send({ type: 'moduleQueued', id: testModule.id, ...this.moduleInfo(testModule) })
  }

  onTestModuleCollected(testModule) {
    this.sendModule(testModule)
  }

  onTestCaseReady(testCase) {
    this.markStarted(testCase.id)
  }

  onTestCaseResult(testCase) {
    const result = testCase.result()
    const diagnostic = testCase.diagnostic?.()
    this.sendResult(testCase.id, result.state, diagnostic?.duration, result.errors, result.note)
  }

  onTestSuiteResult(testSuite) {
    this.sendSuiteErrors(testSuite.id, testSuite.errors?.())
  }

  onTestModuleEnd(testModule) {
    // A module which failed before its collection (a syntax error...) has no collected event.
    this.sendModule(testModule)
    for (const testCase of testModule.children.allTests()) {
      // Tests without a result: a failed beforeAll or an error of the module.
      if (!this.finished.has(testCase.id)) {
        this.onTestCaseResult(testCase)
      }
    }
    let errors = testModule.errors?.()
    if (!errors || errors.length === 0) {
      // Vitest 3 keeps the errors of a module which could not be loaded on its task only.
      errors = testModule.task?.result?.errors
    }
    this.sendSuiteErrors(testModule.id, errors, testModule.diagnostic?.()?.duration)
  }

  async onTestRunEnd(testModules, unhandledErrors, reason) {
    // Vitest 3 does not end the modules which could not be loaded.
    for (const testModule of testModules ?? []) {
      this.onTestModuleEnd(testModule)
    }
    await this.endRun(unhandledErrors, reason)
  }

  moduleInfo(testModule) {
    const file = testModule.moduleId
    return {
      file: toPosix(file),
      name: this.relativeFile(file),
      project: testModule.project?.name || undefined,
    }
  }

  sendModule(testModule) {
    if (this.sentModules.has(testModule.id)) {
      return
    }
    this.sentModules.add(testModule.id)
    const children = []
    for (const child of testModule.children) {
      children.push(this.node(child, []))
    }
    this.send({ type: 'module', id: testModule.id, ...this.moduleInfo(testModule), children })
  }

  node(task, parentNames) {
    const names = [...parentNames, task.name]
    const node = {
      id: task.id,
      kind: task.type === 'suite' ? 'suite' : 'test',
      name: task.name,
      names,
      mode: task.options?.mode,
      each: task.options?.each || undefined,
      line: task.location?.line,
      column: task.location?.column,
    }
    if (task.type === 'suite') {
      node.children = []
      for (const child of task.children) {
        node.children.push(this.node(child, names))
      }
    }
    return node
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Vitest 1 and 2
  // ---------------------------------------------------------------------------------------------------------------

  onCollected(files) {
    if (this.modern || !files) {
      return
    }
    for (const file of files) {
      this.sendLegacyModule(file)
    }
  }

  onTaskUpdate(packs) {
    if (this.modern || !packs) {
      return
    }
    for (const pack of packs) {
      const [id, result] = pack
      const task = this.vitest?.state?.idMap?.get(id)
      if (task && result) {
        this.legacyUpdate(task, result)
      }
    }
  }

  async onFinished(files, errors) {
    if (this.modern) {
      return
    }
    for (const file of files ?? []) {
      this.sendLegacyModule(file)
      this.legacyFinish(file)
    }
    await this.endRun(errors, undefined)
  }

  sendLegacyModule(file) {
    if (this.sentModules.has(file.id)) {
      return
    }
    this.sentModules.add(file.id)
    this.send({
      type: 'module',
      id: file.id,
      file: toPosix(file.filepath),
      name: this.relativeFile(file.filepath),
      project: file.projectName || undefined,
      children: (file.tasks ?? []).map(task => this.legacyNode(task, [])),
    })
  }

  legacyNode(task, parentNames) {
    const names = [...parentNames, task.name]
    const node = {
      id: task.id,
      kind: task.type === 'suite' ? 'suite' : 'test',
      name: task.name,
      names,
      mode: task.mode,
      each: task.each || undefined,
      line: task.location?.line,
      column: task.location?.column,
    }
    if (task.type === 'suite') {
      node.children = (task.tasks ?? []).map(child => this.legacyNode(child, names))
    }
    return node
  }

  legacyUpdate(task, result) {
    if (task.type === 'test' || task.type === 'custom') {
      if (result.state === 'run') {
        this.markStarted(task.id)
      }
      else if (['pass', 'fail', 'skip', 'todo'].includes(result.state)) {
        this.sendResult(task.id, legacyState(result.state), result.duration, result.errors, undefined)
      }
    }
    else if (result.state === 'fail') {
      if (task.type === 'suite' && task.file) {
        this.sendLegacyModule(task.file)
      }
      else if (!task.file) {
        this.sendLegacyModule(task)
      }
      this.sendSuiteErrors(task.id, result.errors, result.duration)
    }
  }

  legacyFinish(task) {
    if (task.type === 'test' || task.type === 'custom') {
      if (!this.finished.has(task.id)) {
        const state = task.result ? legacyState(task.result.state) : 'skipped'
        this.sendResult(task.id, state, task.result?.duration, task.result?.errors, undefined)
      }
      return
    }
    for (const child of task.tasks ?? []) {
      this.legacyFinish(child)
    }
    if (task.result?.state === 'fail') {
      this.sendSuiteErrors(task.id, task.result.errors, task.result.duration)
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Shared
  // ---------------------------------------------------------------------------------------------------------------

  markStarted(id) {
    if (!this.started.has(id)) {
      this.started.add(id)
      this.send({ type: 'testStart', id })
    }
  }

  sendResult(id, state, duration, errors, note) {
    if (this.finished.has(id) || state === 'pending' || state === 'run') {
      return
    }
    this.finished.add(id)
    this.started.add(id)
    this.send({
      type: 'testEnd',
      id,
      state: state === 'passed' || state === 'failed' ? state : 'skipped',
      duration: typeof duration === 'number' ? Math.round(duration) : undefined,
      errors: serializeErrors(errors),
      note: note || undefined,
    })
  }

  sendSuiteErrors(id, errors, duration) {
    const serialized = serializeErrors(errors)
    if (serialized && !this.failedSuites.has(id)) {
      this.failedSuites.add(id)
      this.send({ type: 'suiteError', id, errors: serialized, duration })
    }
  }

  async endRun(errors, reason) {
    if (this.ended) {
      return
    }
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

function legacyState(state) {
  switch (state) {
    case 'pass':
      return 'passed'
    case 'fail':
      return 'failed'
    default:
      return 'skipped'
  }
}
