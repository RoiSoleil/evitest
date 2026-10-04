'use strict'

// The reporter of Vitest: what does not depend on the version of Vitest.

const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { test } = require('node:test')
const { listen } = require('./server.cjs')

test('the hello tells the framework and its version, also for Vitest 1 which has no version on its object', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'evitest-vitest-'))
  fs.mkdirSync(path.join(root, 'node_modules', 'vitest'), { recursive: true })
  fs.writeFileSync(path.join(root, 'node_modules', 'vitest', 'package.json'), JSON.stringify({ version: '1.6.1' }))
  try {
    const server = await listen()
    const { default: Reporter } = await import('../evitest-reporter.mjs')
    const vitest1 = new Reporter()
    vitest1.onInit({ config: { root } })
    await vitest1.onFinished([], [])
    const vitest5 = new Reporter()
    vitest5.onInit({ version: '5.0.3', config: { root } })
    await vitest5.onFinished([], [])
    await server.close()
    const hellos = server.events.filter(event => event.type === 'hello')
    assert.deepEqual(hellos.map(hello => [hello.framework, hello.version, hello.vitest]),
      [['Vitest', '1.6.1', '1.6.1'], ['Vitest', '5.0.3', '5.0.3']])
  }
  finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})
