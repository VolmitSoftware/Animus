# Synthetic Denizen scripts for the converter fixtures. Not taken from any server.
# World events of every kind the converter maps, with cancels, rate limits and player-list targets.
areas:
    type: world
    debug: false
    events:
        on server start:
        - note <cuboid[world,200,60,200,210,70,210]> as:arena
        on player enters market:
        - flag player market.inside
        - actionbar "<&a>Welcome to the market" targets:<player>
        on player exits market:
        - flag player market.inside:!
        - determine cancelled
        on player enters pond:
        - narrate "You hear frogs near <context.area>."
        on player exits old_mill:
        - narrate "Left the mill by <context.cause>."
        on player enters arena:
        - title "title:<&c>Arena" targets:<server.online_players>
        on player enters split_yard:
        - narrate "never converts"
        on player enters area:
        - narrate "every area"

inventories:
    type: world
    debug: false
    events:
        on player opens inventory:
        - if <context.inventory.inventory_type> != CHEST:
            - stop
        - if !<player.has_flag[vault_locked]>:
            - stop
        - determine cancelled
        on player closes chest:
        - narrate "Closed a <context.inventory.inventory_type>."
        on player clicks in inventory:
        - if <context.slot> > 9:
            - stop
        - narrate "Clicked slot <context.slot> with <context.click>."
        - determine cancelled
        on player clicks diamond in inventory click:shift_left:
        - narrate "No shift moving diamonds."

items:
    type: world
    debug: false
    events:
        on player drops item:
        - if !<player.has_flag[no_drop]>:
            - stop
        - narrate "You cannot drop <context.item.material.name>."
        - determine cancelled
        on player picks up dragon_breath:
        - ratelimit <player> 10s
        - determine passively cancelled
        - title "title:Too hot" targets:<player>
        on player consumes bread:
        - flag player meals:++

blocks:
    type: world
    debug: false
    events:
        on player right clicks chest with:stick:
        - narrate "Tapped a chest at <context.location.x>, <context.location.z>."
        - determine cancelled
        on player left clicks block:
        - if <context.location.world.name> != world:
            - stop
        - narrate "Hit <context.location.material.name>."
        on player places tnt:
        - if <player.has_flag[demolition]>:
            - stop
        - determine cancelled
        on player breaks *_log:
        - flag player logs:++
        on player clicks entity:
        - narrate "never converts"

combat:
    type: world
    debug: false
    events:
        on player damaged by fall:
        - if <player.has_flag[feather_fall]>:
            - determine cancelled
        - narrate "Ouch, <context.damage> damage."
        on player damaged by zombie:
        - narrate "A zombie hit you from <context.damager.entity_type>."
        on player damages entity:
        - narrate "You hit <context.entity.entity_type> for <context.final_damage>."
        on player damages npc:
        - if !<npc.has_flag[protected]>:
            - stop
        - actionbar "<&c>This NPC is protected" targets:<player>
        - determine passively cancelled
        on player damages entity priority:10:
        - if !<context.entity.is_npc>:
            - stop
        - narrate "You hit NPC <npc.name>."
        on player kills zombie:
        - flag player zombie_kills:++
        on player dies:
        - narrate "Down again."

archery:
    type: world
    debug: false
    events:
        on player shoots bow:
        - if <context.force> >= 1:
            - stop
        - narrate "Draw the <context.bow.material.name> fully."
        - determine cancelled
        on pillager shoots item with:crossbow:
        - narrate "A <context.entity.entity_type> fired a <context.projectile.entity_type> from its <context.hand>."
        on snowball launched:
        - narrate "Thrown by <context.shooter.entity_type>."
        on ender_pearl launched in:world_nether:
        - determine cancelled
        on projectile hits:
        - narrate "Something landed at <context.location.x>."
        on arrow hits block:
        - narrate "Struck <context.hit_block.material.name> at <context.hit_block.y>."
        on projectile hits entity:creeper shooter:player:
        - if !<player.has_flag[pacifist]>:
            - stop
        - determine cancelled
        on trident collides with zombie:
        - flag player trident_hits:++
        on player shoots target:
        - narrate "Bullseye, <context.projectile.uuid>."
        on npc shoots bow:
        - narrate "never converts"
        on arrow hits npc:
        - narrate "never converts"

npc_keys:
    type: world
    debug: false
    events:
        on player drops item:
        - if !<player.target.is_npc||false>:
            - stop
        - if !<player.is_sneaking>:
            - stop
        - determine cancelled
        on player swaps items:
        - if !<player.target.is_npc||false>:
            - stop
        - narrate "You swap hands at <player.target.name>."

broadcasts:
    type: task
    debug: false
    definitions: target|crew
    script:
    - narrate "A storm comes!" targets:<server.online_players>
    - title "title:<&e>Storm" targets:<server.online_players.filter[has_flag[storm_alerts]]>
    - actionbar "Your crew needs you" targets:<[crew]>
    - playsound <[target]> sound:entity_experience_orb_pickup
    - narrate "Party time" targets:<server.flag[party].exclude[<player>]>
    - foreach <server.online_players> as:online:
        - if <[online].has_flag[muted]>:
            - foreach next
        - narrate "Hello <[online].name>, you are <[online].is_online>" targets:<[online]>
    - narrate "Done <npc.id>"

npc_story:
    type: assignment
    debug: false
    actions:
        on click:
        - if <player.has_flag[story_done_<npc.id>]>:
            - stop
        - narrate "A story for you."
        - flag player story_done_<npc.id>
