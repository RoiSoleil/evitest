'use strict'

// EVitest reporter of Jasmine: streams the results of a Jasmine run to Eclipse (see evitest-common.cjs).
//
// A helper of Jasmine (--helper): it adds the reporter with the documented jasmine.getEnv().addReporter()
// (https://jasmine.github.io/api/edge/Env.html#addReporter), next to the console reporter of Jasmine. The events are
// the ones of the documented Reporter interface (https://jasmine.github.io/api/edge/Reporter.html): jasmineStarted,
// suiteStarted, specStarted, specDone, suiteDone, jasmineDone, with the filename of the results (Jasmine 5 and newer:
// without it, the tests are not shown).

const { Run, guard, packageVersion, reportCrashes, selectedPattern } = require('./evitest-common.cjs')

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
    // Jasmine 5 gives empty values to the errors which are not comparisons.
    if (expectation.matcherName && (expectation.expected !== undefined || expectation.actual !== undefined)
      && !(expectation.expected === '' && expectation.actual === '')) {
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
    this.pattern = selectedPattern()
    this.suites = new Map()
    reportCrashes('Jasmine', this.run.version)
  }

  jasmineStarted(info) {
    this.run.start()
  }

  suiteStarted(result) {
    const names = this.names(result.parentSuiteId, result.description)
    this.suites.set(result.id, { names, file: result.filename })
    if (result.filename) {
      this.run.suite(result.filename, names)
    }
  }

  specStarted(result) {
    if (result.filename) {
      this.run.testStart(this.run.test(result.filename, this.names(result.parentSuiteId, result.description)))
    }
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
  }

  suiteDone(result) {
    const suite = this.suites.get(result.id)
    if (suite?.file && result.failedExpectations?.length > 0) {
      // A failed beforeAll or afterAll.
      this.run.suiteError(this.run.suite(suite.file, suite.names), expectationErrors(result.failedExpectations))
    }
  }

  async jasmineDone(result) {
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

// Loaded as a helper: Jasmine is the global "jasmine". Loaded by require() (the tests of EVitest): the class only.
if (typeof jasmine !== 'undefined' && typeof jasmine.getEnv === 'function') {
  jasmine.getEnv().addReporter(guard(new EVitestJasmineReporter(), 'Jasmine'))
}

module.exports = EVitestJasmineReporter
