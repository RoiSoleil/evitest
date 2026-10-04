'use strict'

// EVitest reporter of Mocha: streams the results of a Mocha run to Eclipse (see evitest-common.cjs), and prints the
// tests in the console as the spec reporter of Mocha.
//
// Mocha loads all the files before running them: the tree of the tests is sent when the run begins, the tests not run
// (excluded by --grep) are ended as skipped when it ends.

const path = require('node:path')
const { Run, packageVersion, selectedPattern } = require('./evitest-common.cjs')

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

    runner.once(constants.EVENT_RUN_BEGIN, () => {
      this.run.start()
      this.sendTree(runner.suite, [])
    })
    runner.on(constants.EVENT_TEST_BEGIN, (test) => {
      this.run.testStart(this.idOf(test))
    })
    runner.on(constants.EVENT_TEST_PASS, (test) => {
      this.run.testEnd(this.idOf(test), 'passed', test.duration)
    })
    runner.on(constants.EVENT_TEST_PENDING, (test) => {
      this.run.testEnd(this.idOf(test), 'skipped')
    })
    runner.on(constants.EVENT_TEST_FAIL, (test, error) => {
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
    this.endRun().then(() => callback(failures), () => callback(failures))
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
