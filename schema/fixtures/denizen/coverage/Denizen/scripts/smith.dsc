# Synthetic scripts in the shapes Denizen servers use most. Not taken from any server.
smith_assignment:
  type: assignment
  actions:
    on assignment:
    - trigger name:click state:true
  interact scripts:
  - smith_interact

smith_interact:
  type: interact
  steps:
    1:
      click trigger:
        script:
        - if <player.location.distance[<npc.location>]> > 6:
          - narrate "<&7>Come closer."
          - stop
        - animate <npc> animation:arm_swing
        - playsound <player> sound:block_anvil_use volume:<[loud].if_null[1]> pitch:2.5
        - define "lines:->:<&6>Fine work, <player.name>."
        - define "lines:->:<&7>Come back tomorrow."
        - foreach <[lines]> as:line:
          - narrate <[line]>
        - define count:++
        - narrate "Visits today: <[count]>"
        - run smith_reward def:<player>
        - run smith_forge
        - run smith_song def:4|anvil_song

smith_prices:
  type: data
  sword: 12

smith_quote:
  type: task
  definitions: item
  script:
  - define price <script[smith_prices].data_key[<[item]>].if_null[0]>
  - define fallback <script[smith_prices].data_key[shield]||5>
  - define known <script[smith_prices].data_key[sword]||5>
  - narrate "That costs <[price]>, or <[fallback]> and <[known]>."

smith_command:
  type: command
  name: smith
  script:
  - define what <context.args.first||sword>
  - define second <context.args.get[2].if_null[none]>
  - run smith_quote def:<[what]>

smith_reward:
  type: task
  definitions: target
  script:
  - flag <[target]> smith.visits:++ expire:1d
  - flag <[target]> smith.last:<util.random.int[1].to[3]>
  - playeffect effect:villager_happy at:<[target].location.above[2]> quantity:6 offset:0.4 targets:<[target]>
  - playeffect effect:heart at:<player.location.above[2.5]> quantity:3
  - playsound <[target].location> sound:entity_player_levelup volume:0.6
  - flag <server.online_players> smith_event_seen
  - execute as_server "sentinel addtarget player:<[target].name> --id <npc.id>"

smith_helpers:
  type: procedure
  script:
  - determine <server.npcs.filter_tag[<[filter_value].has_flag[forge]>].filter_tag[<[filter_value].is_spawned>]>

smith_forge:
  type: task
  script:
  - if !<player.inventory.contains_item[iron_ingot].quantity[3]>:
    - narrate "<&c>Bring me three iron ingots."
    - stop
  - take iron_ingot quantity:3
  - if <player.inventory.contains_item[<player.flag[smith.metal].if_null[gold_ingot]>]>:
    - narrate "<&6>That metal will do."
  - define helpers <proc[smith_helpers]>
  - foreach <[helpers]> as:helper:
    - narrate "<[helper].name> lends a hand at forge <[helper].flag[forge]>."
    - flag <[helper]> forge_uses:++
  - foreach <server.npcs_assigned[smith_assignment]> as:smith:
    - execute as_server "say Smith #<[smith].id> finished an order"
  - give iron_sword

smith_song:
  type: task
  definitions: pause|tune
  script:
  - define notes <list[block_note_block_bell|block.note_block.chime]>
  - foreach <[notes]> as:note:
    - playsound <player> sound:<[note]> sound_category:records volume:0.8 pitch:<util.random.decimal[0.8].to[1.2]>
    - wait <[pause]>t
  - narrate ""
  - flag player <[tune]>:played expire:<[pause]>s
  - wait <util.random.int[1].to[2]>
  - narrate "<&e>That was <[tune]>."

smith_clock:
  type: task
  definitions: length|apprentice|order
  script:
  - flag player smith_started:<util.time_now>
  - define took <util.time_now.duration_since[<player.flag[smith_started]>].in_seconds.round>
  - define clock <duration[<[length]>].formatted>
  - repeat <[length].as[duration].in_seconds.round>:
    - narrate "<&7>Hammering..."
  - actionbar "<&e>Next order in <player.flag_expiration[smith_order].from_now.formatted.if_null[now]>"
  - define wait_left <[apprentice].flag_expiration[smith_order].from_now.in_seconds.if_null[0]>
  - flag player smith_order duration:<[length]>
  - define metal <[order].before[:]>
  - define amount <[order].after_last[:]>
  - narrate "<element[Forge ready].color_gradient[from=#FF8000;to=yellow]> at <player.location.simple>"
  - random:
    - narrate "<&6>The anvil rings."
    - narrate "<&6>Sparks fly."
    - if <player.is_op>:
      - narrate "<&6>The smith nods at you."

smith_mark:
  type: task
  definitions: apprentice
  script:
  - define anvil <player.location>
  - flag <[anvil]> smith_owner:<player.name>
  - flag <[anvil]> smith_strikes:++
  - if <[anvil].has_flag[smith_owner]>:
    - narrate "<&7>Owned by <[anvil].flag[smith_owner]||nobody>"
  - playeffect effect:heart at:<[apprentice].location.above[2]> quantity:3 targets:<player>
  - playeffect effect:flame at:<[apprentice].location> quantity:4
  - define aim <player.cursor_on>
  - flag <[aim]> smith_marked

smith_supplies:
  type: task
  definitions: orders
  script:
  - define blade <item[iron_sword]>
  - adjust def:blade "enchantments:[sharpness=2;unbreaking=1]"
  - adjust def:blade "display:<&7>Smith's Blade"
  - define kit:->:<item[bread[quantity=4]]>
  - define kit:->:<[blade]>
  - foreach <[kit]> as:part:
    - if <player.inventory.can_fit[<[part]>]>:
      - give <[part]>
    - else:
      - narrate "<&c>Your pack is full."
  - give <[orders]>
  - foreach <player.inventory.list_contents> as:held:
    - if <[held].material.name> == iron_ingot && <[held].display.strip_color||plain> == plain:
      - narrate "<&7>You carry <[held].quantity> iron."

smith_watch:
  type: task
  definitions: visitor
  script:
  - note cu@world,0,60,0,12,72,12 as:smith_floor
  - define forge <cuboid[smith_floor].if_null[none]>
  - define glow <npc.location.above[2.5]>
  - define crowd <npc.location.find_players_within[6]>
  - if <[visitor].location.is_within[<[forge]>]>:
    - narrate "<&7>Mind the anvil."
  - if !<player.exists>:
    - stop

smith_board:
  type: task
  definitions: crew
  script:
  - bossbar create smith_heat<player.uuid> "title:<&6>Forge heat" players:<player> progress:0.4 color:red style:segmented_10
  - bossbar update smith_heat<player.uuid> progress:<util.random_decimal>
  - bossbar remove smith_heat<player.uuid>
  - sidebar set "title:<&e>Smithy" "values:Iron|Gold|Coal" players:<[crew]>
  - sidebar remove players:<player>

smith_crew:
  type: task
  definitions: apprentice|caller
  script:
  - define partner <server.match_player[<[caller]>]||nobody>
  - define aim <player.cursor_on[16]>
  - flag <[aim]> smith_anvil
  - if <[aim].has_flag[smith_anvil]||false>:
    - narrate "<&7>Anvil marked."
  - if <[aim].material.name> == anvil:
    - narrate "<&7>That is an anvil."
  - foreach <server.npcs_flagged[forge]> as:helper:
    - look <[helper]> <player.location> duration:2s
    - animate <[helper]> animation:arm_swing
    - playeffect effect:flame at:<[helper].location.above[2]> quantity:4
  - define bench <npc.location>
  - teleport <npc> <[bench].above[1]>
  - lookclose state:true range:8 realistic
  - explode power:1 <[bench]>
  - sidebar add "values:Hammer|Tongs"
  - sidebar set_line scores:1 "values:Anvil"
  - run smith_song player:<[apprentice]>

smith_chest:
  type: world
  events:
    on player closes inventory:
    - if !<context.inventory.location.exists>:
      - stop
    - foreach <context.inventory.list_contents> as:item:
      - if <[item].material.name> != air:
        - give <[item]>
    - foreach <server.npcs_flagged[forge]> as:helper:
      - run smith_song npc:<[helper]>
