// Exits 1, naming each one, if any of the given ports is taken on 127.0.0.1. Binding is the test, so it means the
// same on Linux and macOS.
import net from 'node:net'

const busy = []
for (const port of process.argv.slice(2).map(Number)) {
  const free = await new Promise(resolve => {
    const s = net.createServer().once('error', () => resolve(false)).once('listening', () => s.close(() => resolve(true)))
    s.listen(port, '127.0.0.1')
  })
  if (!free) busy.push(port)
}
if (busy.length) console.error(`in use: ${busy.join(', ')}`)
process.exit(busy.length ? 1 : 0)
