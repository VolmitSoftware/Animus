# Synthetic Denizen scripts for the damage trigger fixture. Not taken from any server.
sparring_assignment:
    type: assignment
    actions:
        on assignment:
        - trigger name:click state:true
        - trigger name:damage state:true
        on click:
        - narrate "Hit me when you are ready, <player.name>."
        on damage:
        - narrate "<npc.name> staggers back."
        on damaged:
        - chat "Watch it!"
    interact scripts:
    - sparring_rounds

sparring_rounds:
    type: interact
    steps:
        warm_up*:
            click trigger:
                script:
                - narrate "Three clean hits and we are done."
            damage trigger:
                script:
                - narrate "Good hit, <player.name>. That was <context.damage> damage."
                - narrate "You swing like a <context.damager.name>."
                - flag player sparring_hits:++
                - give bread quantity:1
                - if <player.flag[sparring_hits]> >= 3:
                    - zap cooled_down
        cooled_down:
            damage trigger:
                script:
                - narrate "Enough for today, <player.name>."
                - flag player sparring_done:true

dummy_assignment:
    type: assignment
    interact scripts:
    - dummy_hits

dummy_hits:
    type: interact
    steps:
        only:
            damage trigger:
                script:
                - narrate "The dummy creaks."
                - flag player dummy_hits:++
