'use strict'

// EVitest reporter of Mocha: streams the results of a Mocha run to Eclipse (see evitest-common.cjs), and prints the
// tests in the console as the spec reporter of Mocha.
//
// Uses the documented reporter API of Mocha (https://mochajs.org/api/tutorial-custom-reporter): the events of
// Mocha.Runner.constants on the runner, the Spec reporter for the console, and the done() method of the reporters,
// which Mocha awaits before exiting. Mocha loads all the files before running them: the tree of the tests is sent when
// the run begins, the tests not run (excluded by --grep) are ended as skipped when it ends.

const path = require('node:path')
const { Run, packageVersion, safe, selectedPattern } = require('./evitest-common.cjs')

/** Mocha, as loaded by the command line of the project. */
function loadMocha() {
  return require(require.resolve('mocha', { paths: [process.cwd()] }))
}

class EVitestMochaReporter {
  constructor(runner, options) {
    const Mocha = loadMocha()
    const constants = Mocha.Runner.constants
    // The console output of the spec reporter of Mocha.
    this.console = new Mocha.reporters.Spec(runner, options)
    this.run = new Run('Mocha', packageVersion('mocha'), process.cwd())
    this.pattern = selectedPattern()
    this.ids = new Map()
    // The listeners never throw: an error of EVitest does not stop the tests.
    const on = (event, name, listener) => runner.on(event, safe('Mocha', name, listener))

    on(constants.EVENT_RUN_BEGIN, 'run begin', () => {
      this.run.start()
      this.sendTree(runner.suite, [])
    })
    on(constants.EVENT_TEST_BEGIN, 'test begin', (test) => {
      this.run.testStart(this.idOf(test))
    })
    on(constants.EVENT_TEST_PASS, 'test pass', (test) => {
      this.run.testEnd(this.idOf(test), 'passed', test.duration)
    })
    on(constants.EVENT_TEST_PENDING, 'test pending', (test) => {
      this.run.testEnd(this.idOf(test), 'skipped')
    })
    on(constants.EVENT_TEST_FAIL, 'test fail', (test, error) => {
      if (test.type === 'hook') {
        // A failed hook fails its suite; Mocha does not run the tests of the suite.
        const suite = test.parent
        const id = suite && suite.root ? this.moduleOf(test.file ?? suite.file) : this.ids.get(suite)
        if (id) {
          this.run.suiteError(id, [error])
        }
        else {
          this.hookErrors ??= []
          this.hookErrors.push(error)
        }
        return
      }
      this.run.testEnd(this.idOf(test), 'failed', test.duration, [error])
    })
  }

  /** Mocha waits for this method before exiting: the connection is closed after the last event. */
  done(failures, callback) {
    let ended
    try {
      ended = this.endRun()
    }
    catch (error) {
      ended = Promise.reject(error)
    }
    ended.catch(error => process.stderr.write(`[EVitest] The reporter of EVitest for Mocha failed: ${error?.stack ?? error}\n`))
      .finally(() => callback(failures))
  }

  async endRun() {
    for (const [test, id] of this.ids) {
      if (test.type === 'test' && !this.run.testsEnded.has(id)) {
        // Not run: excluded by --grep, or after a failed hook.
        this.run.testEnd(id, 'skipped')
      }
    }
    await this.run.end(this.hookErrors)
  }

  moduleOf(file) {
    return file ? this.run.module(file) : undefined
  }

  /** Sends the suites and the tests of a suite, grouped by file (the root suite has the tests of all the files). */
  sendTree(suite, names) {
    for (const test of suite.tests) {
      const file = test.file ?? suite.file
      if (!file) {
        continue
      }
      const testNames = [...names, test.title]
      const selected = !this.pattern || this.pattern.test(testNames.join(' '))
      const id = this.run.test(path.resolve(file), testNames, { mode: test.pending && selected ? 'skip' : undefined })
      this.ids.set(test, id)
    }
    for (const child of suite.suites) {
      const file = child.file
      const childNames = [...names, child.title]
      if (file) {
        this.ids.set(child, this.run.suite(path.resolve(file), childNames))
      }
      this.sendTree(child, childNames)
    }
  }

  idOf(test) {
    let id = this.ids.get(test)
    if (!id) {
      // A test added while running (dynamic tests).
      const names = test.titlePath()
      id = this.run.test(path.resolve(test.file ?? 'unknown'), names)
      this.ids.set(test, id)
    }
    return id
  }
}

module.exports = EVitestMochaReporter
