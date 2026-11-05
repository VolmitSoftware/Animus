# Synthetic Denizen scripts for the converter fixtures. Not taken from any server.
guard_assignment:
    type: assignment
    debug: false
    actions:
        on assignment:
        - trigger name:click state:true
        - trigger name:proximity state:true radius:6
        - equip <npc> chest:iron_chestplate
        - flag npc guard_post:true
        on click:
        - narrate "<&a>Hello <player.name>, I am <npc.name>."
        - if <player.has_flag[met_guard]>:
            - chat "Welcome back."
            - stop
        - flag player met_guard
        - flag player visits:++
        - run greet_task
        - run tally_task def:<player.name>|2 save:tally
        - narrate "You have <entry[tally].created_queue.determination> marks."
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
    - foreach <script[guard_data].data_key[gear]> as:piece:
        - if <[piece]> == shield:
            - foreach next
        - narrate "<[loop_index]>. <[piece].to_uppercase>"
    - choose <util.random.int[1].to[3]>:
        - case 1:
            - chat "A fine day."
        - case 2 3:
            - chat "Stay alert."
    - while <[count]> > 0:
        - define count:--
    - give lantern_token quantity:<[count].add[1]>
    - adjust <npc> name:Gate<&sp>Guard
    extra:
    - narrate "Extra path."
    - teleport <player> <npc.location>
    - adjust <npc> skin_blob:abc

tally_task:
    type: task
    definitions: who|amount
    script:
    - flag player marks:+:<[amount]> expire:1d
    - flag player names:->:<[who]>
    - if <player.flag[marks]||0> > 10 || ( <player.has_flag[names]> && !<player.has_flag[banned]> ):
        - determine <proc[double_proc].context[<player.flag[marks]>]>
    - determine <player.flag[marks]>

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
        on player chats message:*gate*:
        - narrate "The gate opens at dawn." targets:<player>
        - title title:<&e>Gatehouse subtitle:<context.message> stay:3s
        on player joins in:world_nether:
        - actionbar "Mind the heat"
        - playsound <player> sound:block_fire_ambient
        on player clicks in town_shop:
        - determine cancelled
        on npc spawns:
        - announce "An NPC woke up."
        on server start:
        - flag server open:true
        - announce to_console "Town scripts loaded"

double_proc:
    type: procedure
    definitions: number
    script:
    - determine <[number].mul[2]>

reset_command:
    type: command
    name: guardreset
    description: Resets the guard greeting
    usage: /guardreset (player)
    aliases:
    - gr
    permission: town.reset
    script:
    - flag player met_guard:!
    - if <context.args.size> > 0:
        - narrate "Reset for <context.args.first>."
    - else:
        - narrate "Reset."
    - inventory open d:gate_menu

town_guide:
    type: book
    title: Town guide
    author: The Mayor
    signed: true
    text:
    - Welcome to town, <player.name>.
    - The gate closes at dusk.

lantern_token:
    type: item
    material: torch
    display name: <&6>Lantern token
    lore:
    - Shown at the gate

gate_menu:
    type: inventory
    inventory: chest
    title: <&8>Gatehouse
    size: 27
    gui: true
    definitions:
        pane: gray_stained_glass_pane
    slots:
    - [pane] [pane] [pane] [pane] [pane] [pane] [pane] [pane] [pane]
    - [pane] [] [lantern_token] [] [shield] [] [iron_sword[quantity=2]] [] [pane]
    procedural items:
    - determine <list[stone]>

lantern_format:
    type: format
    format: <&7>[Lantern] <[text]>
