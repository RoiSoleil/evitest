'use strict'

// EVitest reporter of Playwright Test: streams the results of a run to Eclipse (see evitest-common.cjs).
//
// Playwright gives the tree of the tests when the run begins: the projects, the files, the describes and the tests.
// A test runs once per project: its file is shown once per project, as the projects of Vitest.

const { Run, packageVersion, stripAnsi } = require('./evitest-common.cjs')

/** The errors of a test result of Playwright. */
function resultErrors(result) {
  const errors = result.errors?.length > 0 ? result.errors : result.error ? [result.error] : []
  return errors.map((error) => {
    const message = stripAnsi(error.message ?? error.value ?? 'Error')
    const firstLine = message.split('\n')[0]
    const match = /^(\w*(?:Error|Exception)\w*):\s?/.exec(firstLine)
    const stack = stripAnsi(error.stack ?? '')
    const frames = stack.split('\n').filter(line => /^\s+at\s/.test(line))
    return {
      name: match ? match[1] : 'Error',
      message: match ? message.slice(match[0].length) : message,
      trace: frames.length > 0 ? `${message}\n${frames.join('\n')}` : message,
      matcherResult: /expect\(/.test(message) ? {} : undefined,
    }
  })
}

class EVitestPlaywrightReporter {
  constructor() {
    this.run = new Run('Playwright Test', packageVersion('@playwright/test') ?? packageVersion('playwright'),
      process.cwd())
    this.tests = new Map()
    this.errors = []
  }

  printsToStdio() {
    return false
  }

  onBegin(config, suite) {
    this.run.start()
    for (const project of suite.suites) {
      for (const file of project.suites) {
        this.sendSuite(file, project.title, [])
      }
    }
  }

  sendSuite(suite, project, names) {
    const file = suite.location?.file
    if (!file) {
      return
    }
    if (suite.type === 'file') {
      this.run.module(file, project || undefined)
    }
    for (const entry of suite.entries ? suite.entries() : [...suite.suites, ...suite.tests]) {
      if (entry.type === 'test' || typeof entry.outcome === 'function') {
        const testNames = [...names, entry.title]
        const skipped = entry.expectedStatus === 'skipped'
        const id = this.run.test(file, testNames, {
          line: entry.location?.line,
          column: entry.location?.column,
          mode: skipped ? 'skip' : undefined,
          project: project || undefined,
        })
        this.tests.set(entry, id)
      }
      else {
        const childNames = [...names, entry.title]
        this.run.suite(file, childNames, {
          line: entry.location?.line,
          column: entry.location?.column,
          project: project || undefined,
        })
        this.sendSuite(entry, project, childNames)
      }
    }
  }

  onTestBegin(test) {
    const id = this.tests.get(test)
    if (id) {
      this.run.testStart(id)
    }
  }

  onTestEnd(test, result) {
    const id = this.tests.get(test)
    if (!id) {
      return
    }
    if (result.retry < test.retries && result.status !== 'passed' && result.status !== 'skipped') {
      // Retried: the last result counts.
      return
    }
    const outcome = typeof test.outcome === 'function' ? test.outcome() : undefined
    let state
    if (result.status === 'skipped' || outcome === 'skipped') {
      state = 'skipped'
    }
    else if (outcome === 'expected' || outcome === 'flaky' || result.status === test.expectedStatus) {
      state = 'passed'
    }
    else {
      state = 'failed'
    }
    let errors = state === 'failed' ? resultErrors(result) : undefined
    if (state === 'failed' && errors.length === 0) {
      errors = [{ name: 'Error', message: `Expected to ${test.expectedStatus}, but ${result.status}.` }]
    }
    this.run.testEnd(id, state, result.duration, errors)
  }

  onError(error) {
    this.errors.push({
      name: 'Error',
      message: stripAnsi(error.message ?? error.value ?? 'Error').split('\n')[0],
      trace: stripAnsi([error.message ?? error.value, error.stack].filter(Boolean).join('\n')),
    })
  }

  async onEnd(result) {
    for (const id of this.tests.values()) {
      if (!this.run.testsEnded.has(id)) {
        // Not run: the run was interrupted, or ended by --max-failures.
        this.run.testEnd(id, 'skipped')
      }
    }
    await this.run.end(this.errors, result?.status === 'interrupted' ? 'interrupted' : undefined)
  }
}

module.exports = EVitestPlaywrightReporter
