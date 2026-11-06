import { readFile, writeFile, unlink } from 'node:fs/promises'
import path from 'node:path'

// Run prepare, stop/restart the managed instance, then run verify with the same username.
export default {
  name: 'animus-definitions',
  description: 'Animus public administration commands and durable JSON definitions across a managed restart',
  async run(context) {
    const { command, expect, server, step, options } = context
    expect(server.directory, 'Requires an isolated Multiplexor instance')
    const phase = options.command ?? 'prepare'
    expect(['prepare', 'verify'].includes(phase), 'Use --command prepare or --command verify')
    context.report.animus = { phase }
    const marker = path.join(server.directory, 'plugins', 'Animus', 'state', 'multiplexor-qa.json')
    const document = path.join(server.directory, 'plugins', 'Animus', 'npcs', 'qa', 'multiplexor-proof.json')
    await step('Plugin status and explicit packet capability', async () => {
      await command('/animus status', /Animus 0\.1\.0 \| BUKKIT \| Minecraft /)
      const status = await command('/animus proof status', /Packet proof (ready|unavailable on Folia)/)
      context.report.animusCapability = status
    })
    if (phase === 'prepare') {
      await step('Create, edit, clone, inspect and validate a dormant definition', async () => {
        await command('/animus create multiplexor-proof minecraft:player world 0 64 0 qa "Multiplexor Original"', /Created dormant multiplexor-proof;/)
        await command('/animus edit multiplexor-proof {"name":"Multiplexor Edited"}', /Saved[^;]*; tx /)
        await command('/animus inspect multiplexor-proof', /multiplexor-proof \| Multiplexor Edited \| minecraft:player/)
        await command('/animus clone multiplexor-proof multiplexor-clone', /Cloned [^;]*; tx /)
        await command('/animus delete multiplexor-clone', /Deleted [^;]*; tx /)
        await command('/animus validate', /All definitions valid; 1 active/)
        await command('/animus reload', /Reloaded; 1 active/)
      })
      await step('Record persisted UUID and revision for the restart phase', async () => {
        const saved = JSON.parse(await readFile(document, 'utf8'))
        expect(saved.name === 'Multiplexor Edited' && saved.revision === 2 && saved.spawned === false, 'Expected edited dormant JSON definition', saved)
        await writeFile(marker, JSON.stringify({ uuid: saved.uuid, revision: saved.revision }) + '\n', { flag: 'wx' })
      })
    } else {
      await step('Verify the same UUID, revision and name survived restart', async () => {
        const before = JSON.parse(await readFile(marker, 'utf8'))
        const saved = JSON.parse(await readFile(document, 'utf8'))
        expect(saved.uuid === before.uuid && saved.revision === before.revision && saved.name === 'Multiplexor Edited' && saved.spawned === false, 'Restart changed the durable definition', saved)
        await command('/animus inspect multiplexor-proof', /multiplexor-proof \| Multiplexor Edited \| minecraft:player/)
        await command('/animus delete multiplexor-proof', /Deleted [^;]*; tx /)
        await command('/animus validate', /All definitions valid; 0 active/)
        await unlink(marker)
      })
    }
  }
}
