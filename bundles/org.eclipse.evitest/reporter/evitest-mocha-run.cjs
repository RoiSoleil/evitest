'use strict'

// Runs Mocha on the files chosen by Eclipse: node evitest-mocha-run.cjs <bin/mocha.js of the project> [arguments].
//
// Mocha adds the files of its command line to the "spec" of its configuration (.mocharc.*, package.json) instead of
// replacing them (documented: https://mochajs.org/#spec): one test file could not run alone. When EVITEST_SPEC has the
// files to run (a JSON array), this script gives Mocha the configuration of the project without its "spec", then the
// files.
//
// The configuration is read by the functions loadRc and loadPkgRc of lib/cli/options of Mocha (marked public in its
// sources, but not exported by its package). If they are missing or fail in a version of Mocha, the files are given
// as the documented command line does: the spec of the configuration runs with them, and a warning says so.

const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { pathToFileURL } = require('node:url')
const { packageVersion, reportCrashes } = require('./evitest-common.cjs')

function loadOptionsModule(mochaFolder) {
  for (const name of ['options.cjs', 'options.js']) {
    const file = path.join(mochaFolder, 'lib', 'cli', name)
    if (fs.existsSync(file)) {
      return require(file)
    }
  }
  return undefined
}

/**
 * The configuration of the project (.mocharc.* over package.json) without its spec, null if it cannot be read with
 * this version of Mocha.
 */
function configurationWithoutSpec(mochaFolder) {
  try {
    const options = loadOptionsModule(mochaFolder)
    if (typeof options?.loadRc !== 'function' || typeof options?.loadPkgRc !== 'function') {
      return null
    }
    const configuration = { ...(options.loadPkgRc({}) ?? {}), ...(options.loadRc({}) ?? {}) }
    delete configuration.spec
    delete configuration._
    // A configuration of JavaScript may have functions: they are not given to Mocha.
    return JSON.parse(JSON.stringify(configuration))
  }
  catch (error) {
    process.stderr.write(`[EVitest] The configuration of Mocha could not be read: ${error?.message ?? error}\n`)
    return null
  }
}

async function main() {
  const [mochaBin, ...args] = process.argv.slice(2)
  if (!mochaBin) {
    process.stderr.write('Usage: node evitest-mocha-run.cjs <mocha executable> [arguments]\n')
    process.exitCode = 2
    return
  }
  let files = []
  try {
    files = JSON.parse(process.env.EVITEST_SPEC || '[]')
  }
  catch {
    files = []
  }
  let mochaArgs = args
  if (files.length > 0) {
    const configuration = configurationWithoutSpec(path.resolve(path.dirname(mochaBin), '..'))
    if (configuration) {
      const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'evitest-mocha-')), 'mocharc.json')
      fs.writeFileSync(file, JSON.stringify(configuration))
      process.on('exit', () => fs.rmSync(path.dirname(file), { recursive: true, force: true }))
      mochaArgs = ['--config', file, '--no-package', ...args]
    }
    else {
      process.stderr.write('[EVitest] This version of Mocha is not known by EVitest: the spec files of its'
        + ' configuration run with the chosen ones.\n')
    }
    mochaArgs = [...mochaArgs, ...files]
  }
  reportCrashes('Mocha', packageVersion('mocha'))
  process.argv = [process.argv[0], mochaBin, ...mochaArgs]
  await import(pathToFileURL(path.resolve(mochaBin)).href)
}

main().catch((error) => {
  process.stderr.write(`${error?.stack ?? error}\n`)
  process.exitCode = 1
})
