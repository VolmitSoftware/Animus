guard_data:
    type: data
    wanted: something else
    gear:
    - sword
    - shield
    - helmet

gate_task:
    type: task
    script:
    - if <server.has_flag[event_open]>:
        - narrate "The gate is open."
    - flag server event_open:false
    - wait 30t
    - walk <npc> l@4,64,8,world
