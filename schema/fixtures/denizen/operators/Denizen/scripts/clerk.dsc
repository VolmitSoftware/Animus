# Synthetic: an operator command beside flags with computed expiry, a computed wait and an NPC list. Not taken from any server.
clerk_assignment:
  type: assignment
  actions:
    on assignment:
    - trigger name:click state:true
  interact scripts:
  - clerk_interact

clerk_interact:
  type: interact
  steps:
    1:
      click trigger:
        script:
        - define minutes 5
        - if <npc.has_flag[clerk_open]>:
          - narrate "<&6>Clerk<&7>: The desk is open."
        - else:
          - flag <npc> clerk_open:true expire:<[minutes]>m
          - execute as_op "time set day"
          - narrate "<&6>Clerk<&7>: Opening the desk."
        - wait <[minutes]>s
        - narrate "<&7><server.npcs_flagged[clerk_open].size> desks open."
        - execute as_player "spawn"
