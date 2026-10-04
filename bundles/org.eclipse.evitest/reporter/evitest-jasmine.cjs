'use strict'

// EVitest reporter of Jasmine: streams the results of a Jasmine run to Eclipse (see evitest-common.cjs). The
// --reporter option of Jasmine replaces its console reporter: this reporter forwards the events to it.

const path = require('node:path')
const { Run, packageVersion, reportCrashes, selectedPattern } = require('./evitest-common.cjs')

/** The console reporter of the installed Jasmine, undefined if it is not found. */
function consoleReporter() {
  const paths = [process.cwd()]
  try {
    paths.unshift(path.dirname(require.resolve('jasmine/package.json', { paths: [process.cwd()] })))
  }
  catch {
    // Not found: the folder of the project.
  }
  // Jasmine 6 and newer, then the older ones.
  for (const name of ['@jasminejs/reporters/console', 'jasmine/lib/reporters/console_reporter.js']) {
    try {
      const loaded = require(require.resolve(name, { paths }))
      const ConsoleReporter = typeof loaded === 'function' ? loaded : loaded.ConsoleReporter ?? loaded.default
      if (typeof ConsoleReporter === 'function') {
        const reporter = new ConsoleReporter()
        reporter.configure?.({})
        reporter.setOptions?.({ print: (...args) => process.stdout.write(args.join('')), showColors: true })
        return reporter
      }
    }
    catch {
      // The next one.
    }
  }
  return undefined
}

/** The errors of the failed expectations of a spec or a suite. */
function expectationErrors(failedExpectations) {
  return (failedExpectations ?? []).map((expectation) => {
    const stack = expectation.stack ?? ''
    const message = expectation.message ?? ''
    const match = /^(\w*(?:Error|Exception)\w*):\s?/.exec(message)
    const error = {
      name: match ? match[1] : expectation.matcherName ? 'AssertionError' : 'Error',
      message: match ? message.slice(match[0].length) : message,
      trace: `${message}\n${stack.split('\n').filter(line => /^\s+at\s/.test(line) && !line.includes('<Jasmine>')).join('\n')}`,
      matcherResult: expectation.matcherName ? {} : undefined,
    }
    if (expectation.expected !== undefined || expectation.actual !== undefined) {
      error.expected = expectation.expected
      error.actual = expectation.actual
    }
    return error
  })
}

/** True for the specs not run because a beforeAll failed: the error is the one of the suite. */
function notRun(result) {
  const expectations = result.failedExpectations ?? []
  return expectations.length > 0 && expectations.every(expectation => /^Not run because a beforeAll/.test(expectation.message ?? ''))
}

class EVitestJasmineReporter {
  constructor() {
    this.run = new Run('Jasmine', packageVersion('jasmine-core') ?? packageVersion('jasmine'), process.cwd())
    this.console = consoleReporter()
    this.pattern = selectedPattern()
    this.suites = new Map()
    reportCrashes('Jasmine', this.run.version)
  }

  forward(method, ...args) {
    try {
      return this.console?.[method]?.(...args)
    }
    catch {
      return undefined
    }
  }

  jasmineStarted(info) {
    this.run.start()
    return this.forward('jasmineStarted', info)
  }

  suiteStarted(result) {
    const names = this.names(result.parentSuiteId, result.description)
    this.suites.set(result.id, { names, file: result.filename })
    if (result.filename) {
      this.run.suite(result.filename, names)
    }
    return this.forward('suiteStarted', result)
  }

  specStarted(result) {
    if (result.filename) {
      this.run.testStart(this.run.test(result.filename, this.names(result.parentSuiteId, result.description)))
    }
    return this.forward('specStarted', result)
  }

  specDone(result) {
    if (result.filename) {
      const names = this.names(result.parentSuiteId, result.description)
      const selected = result.status !== 'excluded' || !this.pattern || this.pattern.test(names.join(' '))
      const mode = result.status === 'pending' || (result.status === 'excluded' && selected && !this.pattern)
        ? 'skip' : undefined
      const id = this.run.test(result.filename, names, { mode })
      const state = result.status === 'passed' ? 'passed'
        : result.status === 'failed' && !notRun(result) ? 'failed' : 'skipped'
      this.run.testEnd(id, state, result.duration ?? undefined,
        state === 'failed' ? expectationErrors(result.failedExpectations) : undefined)
    }
    return this.forward('specDone', result)
  }

  suiteDone(result) {
    const suite = this.suites.get(result.id)
    if (suite?.file && result.failedExpectations?.length > 0) {
      // A failed beforeAll or afterAll.
      this.run.suiteError(this.run.suite(suite.file, suite.names), expectationErrors(result.failedExpectations))
    }
    return this.forward('suiteDone', result)
  }

  async jasmineDone(result) {
    const forwarded = this.forward('jasmineDone', result)
    if (forwarded && typeof forwarded.then === 'function') {
      await forwarded
    }
    const errors = expectationErrors(result?.failedExpectations)
    await this.run.end(errors, result?.overallStatus === 'incomplete' && result.incompleteCode !== 'noSpecsFound'
      && result.incompleteReason && !/fit|fdescribe|focused/i.test(result.incompleteReason) ? 'interrupted' : undefined)
  }

  /** The names of the suites of a parent suite, then the description. */
  names(parentSuiteId, description) {
    const parent = parentSuiteId ? this.suites.get(parentSuiteId) : undefined
    return [...(parent?.names ?? []), description]
  }
}

module.exports = EVitestJasmineReporter
