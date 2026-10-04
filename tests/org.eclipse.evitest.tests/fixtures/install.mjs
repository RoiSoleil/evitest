// Installs the frameworks of the fixtures of the end to end tests: node install.mjs [locked|minimum|latest]
//
// - locked: the versions of the package-lock.json of each fixture (npm ci), the build of the pull requests;
// - minimum, latest: the versions of versions.json, installed over the locked ones without changing the fixtures.
//
// node:test, Bun and Deno need no install here: the builds set up the versions of versions.json for the runtimes.

import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const folder = path.dirname(fileURLToPath(import.meta.url))
const set = process.argv[2] ?? 'locked'
if (!['locked', 'minimum', 'latest'].includes(set)) {
  console.error('Usage: node install.mjs [locked|minimum|latest]')
  process.exit(2)
}
const versions = JSON.parse(readFileSync(path.join(folder, 'versions.json'), 'utf8'))
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'

function run(cwd, ...args) {
  console.log(`[${path.basename(cwd)}] npm ${args.join(' ')}`)
  execFileSync(npm, args, { cwd, stdio: 'inherit', shell: process.platform === 'win32' })
}

for (const [name, { fixture, [set]: version }] of Object.entries(versions.packages)) {
  const cwd = path.join(folder, fixture)
  run(cwd, 'ci', '--no-audit', '--no-fund')
  if (set !== 'locked') {
    run(cwd, 'install', '--no-save', '--no-audit', '--no-fund', `${name}@${version}`)
  }
}
