'use strict'

// EVitest reporter of Jest: streams the results of a Jest run to Eclipse (see evitest-common.cjs).
//
// Uses the documented reporter API of Jest (https://jestjs.io/docs/configuration#custom-reporters): onRunStart,
// onTestFileStart, onTestCaseStart and onTestCaseResult (Jest 29.6 and newer: without them, the results of a file
// arrive with onTestFileResult), onTestFileResult, onRunComplete. The fields read are the ones of the AssertionResult
// and TestResult types of @jest/test-result. failureDetails[].matcherResult (the values compared) is optional.

const { Run, guard, packageVersion, selectedPattern } = require('./evitest-common.cjs')

/** The error of a failed assertion result of Jest: its message with the stack, and the values compared. */
function assertionErrors(result) {
  const messages = result.failureMessages ?? []
  return messages.map((message, index) => {
    const details = result.failureDetails?.[index]
    const matcher = details && typeof details === 'object' ? details.matcherResult : undefined
    const firstLine = message.split('\n')[0]
    const match = /^(\w*(?:Error|Exception)\w*):\s?/.exec(firstLine)
    const error = {
      name: match ? match[1] : 'Error',
      message: message.split(/\n\s+at\s/)[0].replace(/^\w*(?:Error|Exception)\w*:\s?/, ''),
      trace: message,
      matcherResult: matcher,
    }
    if (matcher && 'expected' in matcher && 'actual' in matcher) {
      error.expected = matcher.expected
      error.actual = matcher.actual
    }
    return error
  })
}

class EVitestJestReporter {
  constructor(globalConfig) {
    this.rootDir = globalConfig?.rootDir ?? process.cwd()
    this.run = new Run('Jest', packageVersion('jest', this.rootDir), this.rootDir)
    this.pattern = selectedPattern()
    return guard(this, 'Jest')
  }

  onRunStart() {
    this.run.start()
  }

  onTestFileStart(test) {
    this.run.module(test.path, this.project(test))
  }

  onTestCaseStart(test, info) {
    const id = this.run.test(test.path, [...(info.ancestorTitles ?? []), info.title], { project: this.project(test) })
    this.run.testStart(id)
  }

  onTestCaseResult(test, result) {
    this.sendResult(test, result)
  }

  onTestFileResult(test, results) {
    for (const result of results.testResults ?? []) {
      this.sendResult(test, result)
    }
    const moduleId = this.run.module(test.path, this.project(test))
    if (results.testExecError) {
      const error = results.testExecError
      this.run.suiteError(moduleId, [{
        name: error.name || 'Error',
        message: error.message ?? String(error),
        trace: results.failureMessage ? results.failureMessage : `${error.message}\n${error.stack ?? ''}`,
      }])
    }
  }

  async onRunComplete(contexts, results) {
    const errors = []
    if (results?.runExecError) {
      errors.push(results.runExecError)
    }
    await this.run.end(errors, results?.wasInterrupted ? 'interrupted' : undefined)
  }

  getLastError() {
    return undefined
  }

  /** The name of the Jest project of the test, when there are several of them. */
  project(test) {
    const name = test?.context?.config?.displayName
    return typeof name === 'object' && name ? name.name : name || undefined
  }

  sendResult(test, result) {
    if (this.isSent(test, result)) {
      return
    }
    const names = [...(result.ancestorTitles ?? []), result.title]
    const id = this.run.test(test.path, names, {
      line: result.location?.line,
      column: result.location?.column,
      mode: this.mode(result, names),
      project: this.project(test),
      fresh: true,
    })
    const state = result.status === 'passed' ? 'passed' : result.status === 'failed' ? 'failed' : 'skipped'
    this.run.testEnd(id, state, result.duration ?? undefined, state === 'failed' ? assertionErrors(result) : undefined)
  }

  /** True if the result was already sent: onTestCaseResult then onTestFileResult give the same object. */
  isSent(test, result) {
    this.results ??= new WeakSet()
    if (this.results.has(result)) {
      return true
    }
    this.results.add(result)
    // Jest copies the results of onTestCaseResult in onTestFileResult: they are recognized by their content.
    this.signatures ??= new Set()
    const signature = JSON.stringify([test.path, result.ancestorTitles, result.title, result.status,
      result.location, result.duration, result.failureMessages])
    if (this.signatures.has(signature)) {
      return true
    }
    this.signatures.add(signature)
    return false
  }

  /** The mode of a skipped test: skip or todo, none for the tests not selected by the run. */
  mode(result, names) {
    if (result.status === 'todo') {
      return 'todo'
    }
    if (result.status === 'pending' || result.status === 'skipped' || result.status === 'disabled') {
      return this.pattern && !this.pattern.test(names.join(' ')) ? undefined : 'skip'
    }
    return undefined
  }
}

module.exports = EVitestJestReporter
