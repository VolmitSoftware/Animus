# Synthetic scripts for guards and stops that do not convert. Not taken from any server.
bank_assignment:
  type: assignment
  actions:
    on assignment:
    - trigger name:click state:true
  interact scripts:
  - bank_interact

bank_interact:
  type: interact
  steps:
    1:
      click trigger:
        script:
        - if <player.money> < 100:
          - narrate "You need more money."
          - stop
        - execute as_server "give <player.name> diamond 1"
        - narrate "Paid out."

greeter_assignment:
  type: assignment
  actions:
    on assignment:
    - trigger name:click state:true
  interact scripts:
  - greeter_interact

greeter_interact:
  type: interact
  steps:
    1:
      click trigger:
        script:
        - if <player.money> > 1000:
          - narrate "Welcome, big spender."
        - narrate "Hello <player.name>."
        - execute as_op "warp vip"

reward_task:
  type: task
  script:
  - inject vip_check
  - ratelimit <npc> 10s
  - execute as_server "give <player.name> emerald 1"

vip_check:
  type: task
  script:
  - if !<player.has_flag[vip]>:
    - narrate "VIP only."
    - stop

lobby_world:
  type: world
  events:
    on player joins:
    - foreach <player.money> as:coin:
      - stop
    - execute as_server "give <player.name> bread 1"

reward_command:
  type: command
  name: reward
  permission:
    - town.reward
  script:
  - execute as_server "give <player.name> gold_ingot 1"
