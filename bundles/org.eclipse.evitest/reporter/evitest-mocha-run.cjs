'use strict'

// Runs Mocha on the files chosen by Eclipse: node evitest-mocha-run.cjs <bin/mocha.js of the project> [arguments].
//
// Mocha adds the files of its command line to the "spec" of its configuration (.mocharc.*, package.json) instead of
// replacing them: one test file could not run alone. When EVITEST_SPEC has the files to run (a JSON array), this
// script gives Mocha the configuration of the project without its "spec", then the files.

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

/** The configuration of the project (.mocharc.* over package.json) without its spec, null if there is none. */
function configurationWithoutSpec(mochaFolder) {
  const options = loadOptionsModule(mochaFolder)
  if (!options) {
    return null
  }
  const configuration = { ...(options.loadPkgRc({}) ?? {}), ...(options.loadRc({}) ?? {}) }
  delete configuration.spec
  delete configuration._
  return configuration
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
