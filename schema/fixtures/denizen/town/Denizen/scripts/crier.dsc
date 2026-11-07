# Synthetic Denizen scripts for the converter fixtures. Not taken from any server.
# One world script starts the dialogue an NPC's flag names on a sneaking right click and cancels it;
# a plain right click is left alone, so a shop on the NPC still opens.
dialogue_starter:
    type: world
    debug: false
    events:
        on player right clicks entity bukkit_priority:LOWEST:
        - if !<context.entity.is_npc||false>:
            - stop
        - if <context.hand> == offhand:
            - stop
        - if !<player.is_sneaking>:
            - actionbar "<&e>Sneak and right click <&7>to hear the news" targets:<player>
            - stop
        - if !<npc.has_flag[dialogue_script]||false>:
            - stop
        - if <player.has_flag[story_done_<npc.id>]>:
            - stop
        - ratelimit <player> 2t
        - determine passively cancelled
        - run <npc.flag[dialogue_script]>

crier_assignment:
    type: assignment
    debug: false
    actions:
        on assignment:
        - trigger name:click state:false
        - flag <npc> dialogue_script:crier_story

crier_story:
    type: task
    debug: false
    script:
    - narrate "Extra, extra! The gate opens at dawn, <player.name>."
    - flag player crier_heard

# The shape of a converted newsboy: the story starts only on a sneaking right click on the NPC itself.
sneak_story_starter:
    type: world
    debug: false
    events:
        on player right clicks entity bukkit_priority:LOWEST:
        - if !<context.entity.is_npc||false>:
            - stop
        - if <context.hand> == offhand:
            - stop
        - if !<player.is_sneaking>:
            - stop
        - if !<npc.has_flag[dialogue_script]||false>:
            - stop
        - ratelimit <player> 2t
        - determine passively cancelled
        - run <npc.flag[dialogue_script]>
