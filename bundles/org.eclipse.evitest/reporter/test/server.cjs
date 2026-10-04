'use strict'

// A socket of "Eclipse" for the tests of the reporters: it keeps the events they send.

const net = require('node:net')

/** Listens on a free port, sets EVITEST_PORT, and returns the events received and the connections. */
async function listen() {
  const events = []
  let connections = 0
  const closed = []
  const server = net.createServer((socket) => {
    connections++
    let buffer = ''
    socket.on('data', (data) => {
      buffer += data
      let index
      while ((index = buffer.indexOf('\n')) >= 0) {
        events.push(JSON.parse(buffer.slice(0, index)))
        buffer = buffer.slice(index + 1)
      }
    })
    closed.push(new Promise(resolve => socket.on('close', resolve)))
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  process.env.EVITEST_PORT = String(server.address().port)
  return {
    events,
    port: server.address().port,
    connections: () => connections,
    /** Waits for the connections to be closed by the reporters, then stops listening. */
    async close() {
      await Promise.all(closed)
      delete process.env.EVITEST_PORT
      await new Promise(resolve => server.close(resolve))
    },
  }
}

/** The events of a type. */
function ofType(events, type) {
  return events.filter(event => event.type === type)
}

/** The node events as "kind names" lines, and the ends as "state names" lines, for short assertions. */
function summary(events) {
  const names = new Map()
  const lines = []
  for (const event of events) {
    if (event.type === 'module') {
      names.set(event.id, event.name)
    }
    else if (event.type === 'node') {
      names.set(event.node.id, event.node.names.join(' > '))
      lines.push(`${event.node.kind} ${event.node.names.join(' > ')}${event.node.mode ? ` (${event.node.mode})` : ''}`)
    }
    else if (event.type === 'testEnd') {
      lines.push(`${event.state} ${names.get(event.id)}`)
    }
    else if (event.type === 'suiteError') {
      lines.push(`error ${names.get(event.id)}: ${event.errors[0].message}`)
    }
  }
  return lines
}

module.exports = { listen, ofType, summary }
