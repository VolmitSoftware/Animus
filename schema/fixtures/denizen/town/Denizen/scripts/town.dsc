# Synthetic Denizen scripts for the converter fixtures. Not taken from any server.
guard_assignment:
    type: assignment
    debug: false
    actions:
        on assignment:
        - trigger name:click state:true
        - trigger name:proximity state:true radius:6
        on click:
        - narrate "<&a>Hello <player.name>, I am <npc.name>."
        - if <player.has_flag[met_guard]>:
            - chat "Welcome back."
            - stop
        - flag player met_guard
        - flag player visits:++
        - run greet_task
        on enter proximity:
        - look <npc> <player>
        - playsound <player> sound:entity_villager_yes volume:1 pitch:1.5
        on exit proximity:
        - narrate "Farewell, <player.name>!"
        on spawn:
        - equip <npc> hand:iron_sword head:iron_helmet
        on push:
        - narrate "Hey!"
    interact scripts:
    - guard_quest

guard_quest:
    type: interact
    steps:
        start*:
            click trigger:
                script:
                - narrate "Bring me <script[guard_data].data_key[wanted]>."
            chat trigger:
                1:
                    trigger: /Yes/ I will help
                    script:
                    - narrate "Thank you."
                    - zap gathering
                2:
                    trigger: /regex:no.*/
                    script:
                    - narrate "Shame."
        gathering:
            click trigger:
                script:
                - if <player.flag[visits]> >= 3 && <player.has_permission[town.quest]>:
                    - execute as_server "give <player.name> minecraft:emerald 1"
                    - zap done
                - else if !<npc.is_spawned>:
                    - narrate "How odd."
                - else:
                    - narrate "Come back later."
            proximity trigger:
                entry:
                    script:
                    - narrate "Still gathering?"
                move:
                    script:
                    - narrate "Moving."
        done:
            click trigger:
                script:
                - narrate "<&color[#ffaa00]>You did it<&chr[2764]>"
                - give diamond quantity:2
                - take item:dirt quantity:3
                - zap
            damage trigger:
                script:
                - narrate "Ouch."

greet_task:
    type: task
    script:
    - define count 3
    - narrate "<[count]> greetings."
    - wait 2s
    - playeffect effect:villager_happy at:<npc.location> quantity:10 offset:0.5
    - repeat 2:
        - narrate "Wave."
    - choose <player.flag[visits]>:
        - case 1:
            - narrate "First visit."
        - default:
            - narrate "Again."
    - title title:"Guard" subtitle:"<player.name>" fade_in:1s stay:2s fade_out:1s
    - actionbar "<&e>Visit logged" targets:<player>
    - run greet_task path:extra
    - foreach <server.online_players> as:target:
        - narrate "<[target].name>"
    extra:
    - narrate "Extra path."
    - teleport <player> <npc.location>

guard_data:
    type: data
    wanted: three iron ingots

lantern_world:
    type: world
    events:
        on player joins:
        - narrate "Welcome to the server."
        on delta time secondly every:5:
        - flag server ticks:++

double_proc:
    type: procedure
    definitions: number
    script:
    - determine <[number].mul[2]>

reset_command:
    type: command
    name: guardreset
    script:
    - flag player met_guard:!
