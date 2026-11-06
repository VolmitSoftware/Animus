guard_orders:
  type: task
  script:
  - execute as_server "sentinel addtarget player:<player.name> --id <npc.id>"
  - execute as_server "sentinel removetarget player:<player.name>"
  - execute as_op "sentinel forgive"
  - execute as_server "sentinel guard <player.name>"
  - execute as_server "sentinel guard"
  - trait state:false sentinel
  - trait state:true sentinel
  - execute as_server "sentinel addtarget monsters"
  - execute as_server "sentinel removetarget player:<player.name> --id 12"
  - execute as_server "sentinel range 20"
  - trait sentinel
  - execute as_server "say The guard is ready"
