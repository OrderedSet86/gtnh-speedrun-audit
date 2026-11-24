# Log Format

Each line of `log/audit-*.jsonl` is one JSON object with `v`, `seq`, `sid`, `sidx`, `t` (the event type), `wall`,
`ticks`, `pticks`, `thread`, `data` and `prev`. Each section below is one area, and has the fields of `data` for each
event type of the area. A note such as "before v1.0.0" names the first release with the field. The chain rules and the
verdicts are in [VERIFIERS.md](../VERIFIERS.md).

## Sessions and saves

- `session_start.logTail.cutLastLineIn` names a log file whose last line was cut, such as by a power loss during a
  write. The chain resumes from the line before the cut line, and the verifier's report has the cut line as
  `CORRUPT_LINE`.
- The mod logs a `world_saving` line when overworld saving turns off (`enabled: false`), and when saving turns back on.
  ServerUtilities turns saving off on each world for the length of a backup, and `/save-off` turns saving off too.
  `savingOffDims` lists the worlds that weren't saving at that moment. Some worlds never save, such as Gadomancy's Outer
  Lands (dim 173). `backupRunning` is present when ServerUtilities is installed. Bundles before v0.6.0 don't have these
  lines.
- A `world_saving` line with `enabled: false` also has `lastSave {seq, ticks, wall}`, the anchor values of the
  overworld's latest save. A backup saves each world and then turns saving off, so these values are the anchor inside
  that backup. A `WORLD_ROLLBACK` whose `session_start.anchor` equals a `lastSave` restored that backup.
- `session_end` has the fields of `world_saving`. Dim 0 in `savingOffDims` means that the shutdown save skipped the
  overworld and the anchor, so the next start is a `WORLD_ROLLBACK` to the last save.
- A `backup_done` line has the newest file of ServerUtilities' backup folder when a backup finishes: `file`, `bytes`,
  `lastModified`, and `save` with the values of that `lastSave`. Each backup gets one line. Before v1.1.0, a backup on
  ServerUtilities 2.4.22 (pack 2.9.0) can have two or three lines with the same file, and a backup on ServerUtilities
  2.5 (pack 2.9.1) doesn't have a line.
- The mod logs a `world_saved` line at each overworld save while saving is on. The line has `anchor {seq, ticks, wall}`.
  These saves are the autosave every 900 ticks, `/save-all`, and the save before each backup. The shutdown save after
  `session_end` doesn't get a line. The mod flushes each line to the OS when the mod writes the line, and forces each
  `world_saved` line to disk. After a `CRASH_RECOVERY` start with `loadedSave.kind` `save`, the log is complete up to
  the save that the world came back to. A stretch of more than 900 ticks without `world_saved` is a stretch with saving
  off, or with lines lost. Bundles before v1.0.0 don't have these lines.
- `dim_table` lists the registered dimensions at each start: `id`, provider `type`, `provider` class, `loaded`, and
  `name` and `saveFolder` for a loaded dimension. `dim_loaded` names each dimension that loads later in the session.
  Together the two name each dim id in `savingOffDims`.
- `session_end` has `clientThreadLinesDropped` when the mod dropped lines from the client thread in singleplayer.
- `commands_allowed` is logged at each start. `commandsAllowed` is a singleplayer world's "Allow Cheats" setting, and
  when the setting is on, the world's owner may use commands. When `commandsAllowedForAll` is on, each player may use
  commands. The mod polls both settings each tick, and logs a `commands_allowed_change` line (`setting`, `from`, `to`)
  for each change. `lan_open` is a singleplayer world opened to LAN (`gameType` for players who join, `allowCheats`,
  `port`). Opening to LAN with "Allow Cheats" also turns `commandsAllowedForAll` on.
- The mod polls the game rules each tick, so changes from `/gamerule`, the ServerUtilities config screen and mods get a
  `gamerule_change` line. The `/gamerule` command line just before a change has the player who made
  the change. The values at boot are in `session_start.gamerules`.
- `queue_overflow` lines count the events that the mod dropped while the log writer was 50,000 events behind.
- An `unserializable_event` line stands in for an event whose line couldn't be written. The line has the event `type`
  and the `error` class. The chain goes on after the line.
- `item_id_table` is the world's numeric block and item ids at each start. A key starts with `\u0001` for a block and
  `\u0002` for an item, the prefixes of level.dat. The mod writes the file only when the table changed.
- NBT hashes (`nbtHash`, `beforeHash`, `payloadHash`, `liveHash`) have the format in `session_start.nbtHashFormat`.
  Format 2 covers each value of the NBT. A bundle without `nbtHashFormat` has format 1, where two NBTs can share a hash
  when the two differ only inside a byte array, or by string text that looks like other fields. Compare hashes only
  between sessions of one format.

## World copies

- The first `session_start` of a copy has a `fork` block: `parentUuid`, `parentFolder`, and the `parentSeq`,
  `parentHash`, `parentTicks` and `parentWall` of the copied save. `parentLogPresent` is false when the parent chain's
  directory is missing. `fork.loadedSave` has the shape of `loadedSave`, and points to the copied save in the parent's
  log.
- A `session_start` has `copies` when chains forked from the session's chain: `uuid`, `folder`, `parentSeq`, and the
  copy's `tailSeq` and `tailHash` at that start. When the parent starts next, the mod adds the copy and the copy's
  length to the parent chain.
- `homeFolderSet: true` marks the start that set `homeFolder`: a new world, or the first start on v1.0.0 or later.
- `chains.json` has one entry per chain, the exported chain first: `folder`, `fork` (absent for the first chain),
  `parentLinkOk`, `verdict`, `lines`, `lastSeq` and `lastHash`. The files of each other chain are in
  `chains/<uuid>/log/` and `chains/<uuid>/snapshots/`.
- A snapshot file name ends with the seq of the file's log line, such as `nodes-477200-5123.json.gz`, so a restore that
  replays tick 477200 writes a new file. Before v1.0.0, a name ends in the tick, and the file of a replayed tick
  replaced the earlier file.

## Commands

- A command block gets a `command` line for the first run of each command in a 10-minute window. The windows of two
  command blocks (`dim`, `pos`), or of two commands, are separate. One `command_repeats` line has the count of the
  window's other runs: the command block's fields, `cmd`, `args`, `dropped` (the count of runs) and `windowMs`. Before
  v1.1.0, each run has a `command` line.
- `command_output` lines are the text replies of the commands on the server's `commandOutputCapture` allowlist. The
  default list is `/gt global_energy_display`, `/gamerule`, `/difficulty`, `/powerfails`, `/bq_admin`, `mothership_info`
  and `/backup`. The mod keeps only the replies to the sender, and at most 4 KB per command. The mod doesn't capture
  chat commands.
- `gt_global_energy` lines are `/gt global_energy_add|set|join|display` commands. A line has the team leader of each
  named player, and the wireless EU at dispatch (`before`) and at the end of that tick (`after`). The EU values are
  decimal strings.

## Client packets

- `creative_slot` lines are creative-inventory packets: the client sets a slot of the player's inventory, or drops a
  stack (slot < 0). A move of an item between slots and a take from a creative tab both arrive as slot sets, so each
  line also has the slot's previous contents. Net `item` against `previousKey` over a player's lines to get the spawned
  items. `inCreative: false` is a packet that vanilla ignored, and only a modified client sends such a packet.
- `rate_limited` lines count client packets without an effect, past 20 lines per player and type in a minute: `nbt_edit`
  without a pending edit, `creative_slot` outside creative, `nei_packet`, and `quest_edit` from a player who isn't an
  op. `dropped` is the count for that minute. A high count of `rate_limited` lines comes from a modified client that
  floods the server. The mod logs each packet that changes the world as a line. Bundles before v1.0.0 don't have these
  lines.
- `creative_block` lines are the blocks that a player in creative placed or broke. A line has `action`, `block`, `meta`,
  `itemKey` (for a place) and coordinates. `tile: true` marks a block with a tile entity. A creative placement doesn't
  use up an item, so an inventory snapshot doesn't have the placed block. For a bed or a door, the mod logs each block.
  Matter Manipulator placements are `bulk_placed` lines.

## NEI

- `nei_cheat` `give` lines have `inserted`, the count of items that went into the inventory. `inserted` is 0 when the
  inventory was full, and NEI's client then fills open-container slots with `set_slot`. `set_slot` lines have
  `previous`, the slot's stack before the set, so a delete has the deleted stack. A `delete_all_items` line has the
  deleted stacks in `items`. NEI's load-saved-state also runs `delete_all_items`, and then refills with `set_slot`.
- These are the other `nei_cheat` lines:
  - `toggle_rain`: `dim` and `wasRaining`
  - `toggle_magnet`
  - `cycle_creative_inv`: a row swap with NEI's 54-slot creative+ stash
  - `creative_plus`: the stash opened or closed
  - `modify_enchantment`: a change on NEI's enchantment screen. The line has `enchantment`, `level` and `add`
  - `set_spawner_mob`: a mob spawner's mob, and the spawner's `dim`, `x`, `y` and `z`
  - `disable_action`: a lock per dimension that `CHANGE_PROPERTY` sets

  NEI doesn't check permission for the `set_spawner_mob` packet. When `rain` is disabled, NEI stops rain in the
  dimension. When a time zone (`dawn`, `noon`, `dusk` or `midnight`) is disabled, NEI's time change skips the zone.
- `set_dummy_slot` lines are NEI drags onto a ghost slot, such as the filter slot of an Item Translocator. A line has
  the stack, `slot`, `slotClass`, `container` and `previous`. The stack in a ghost slot is a filter setting, and isn't
  an item.
- NEI calls `set_time`, `toggle_rain`, `set_spawner_mob`, `set_dummy_slot` and `disable_action` without a player, so
  those lines have the uuid and name of the NEI packet's sender. Before v1.0.0, `set_time` lines have a null player.

## Inventories

- `inv_snapshot` lines, and the snapshot files that the lines name, have `dim`, `pos` `[x, y, z]` (the place of the feet
  to the nearest 0.1 block), `yaw` and `pitch` (to 0.1 degree), so a snapshot's armour and the player's place are in one
  record. Bundles before v1.0.0 don't have these fields.
- `death` lines have the `killer` (`player:Name` or the entity name, such as `Zombie`), the `directKiller` when the two
  differ (an arrow), and the death `message`. Before v0.6.0, the lines have only `damageSource`.

## AE2

- Each AE2 census is one `ae2_census` line and one file, `ae2-<ticks>-<seq>.json.gz`. The file has `census` and `grids`.
  `grids` has one entry for each grid with an item, a fluid or a pattern. The line has `census`, `grids` (the count of
  entries), `gridsSkipped` (grids that don't store anything), and the totals `nodes`, `totalItems` and `patternStacks`.
  Before v1.0.0, a census has one `ae2_snapshot` line and one file for each grid, and the empty grids get a line too.
  The grid's fields are on the line, and the line doesn't have `anchor`.
- A grid entry has `gridId`, `anchor`, `nodes`, `distinctItems`, `totalItems`, `distinctFluids`, `craftableItems`,
  `items`, `fluids`, and the pattern fields below. `anchor` is `[dim, x, y, z]`, the block of the grid's ME Controller.
  For a controller of several blocks, `anchor` is the lowest block by dim, x, y and z, and for a grid without a
  controller, the lowest node. `gridId` is AE2's grid storage id. AE2 saves the id in each node, and a grid of newly
  placed nodes gets a new id, so match grids across censuses by `anchor`.
- The census reads each loaded grid within one server tick, so the grids of one `ae2_census` file are from one instant,
  and a difference between two censuses is a change in the network. A census line with `walkTicks` comes from a
  development build that read the grids over several ticks. Read each count of such a census as ± what a factory moves
  in `walkTicks` ticks.
- Census keys are `modid:name@meta`. A key doesn't include NBT, so the census sums the variants of one item into one
  entry. A tool at another durability is such a variant. The totals are exact. `nbtVariantsCollapsed` is the count of
  entries that merged. The inventory snapshots and the `nbt_edit` lines have the NBT.
- A grid entry also has the network's `channelsInUse`, `patternProviders`, `patternStacks` (pattern stacks in provider
  slots) and `distinctPatterns`. `channelsInUse` is -1 when the mod couldn't read the field, and 0 while the network
  boots or has a controller conflict, so read `channelsInUse` with `controllerState` and `booting`.
- A grid entry has `patterns`, with each distinct pattern's `outputs` and whether the pattern is a `crafting` pattern.
  `providers` lists each ME interface (block or part), AE2 Fluid Crafting interface, GT Crafting Input Buffer and GT
  Large Molecular Assembler (the slots of the assembler's pattern hatches). Each provider has the provider's place and
  slots. A slot has the pattern's `author`, the name of the player who encoded the pattern, and the pattern's index in
  `patterns`. AE2 compares patterns without the author, so identical patterns of two players are one entry in `patterns`
  and two slots. A pattern encoded before AE2 wrote the tag doesn't have an author.
- An entry has `craftable: true` when the network could autocraft the item at that instant, from a pattern for the item
  or a crafting level emitter. An entry with `count: 0` is craftable only: AE2's item list of the network has each
  craftable item, and the census has each entry of that list. `craftableItems` on the grid entry is the count of
  craftable entries. Bundles from v0.6.0 and earlier have count-0 entries without the flag. Read those entries as
  craftable.
- `pattern_encoded` lines are patterns encoded in a pattern terminal. A line has the player, the `author` tag that AE2
  wrote, and the stack. `pattern_slot` lines are patterns put into or taken out of an ME interface (`removed`, `added`,
  `author`) or GT's Crafting Input Buffer (`added` only). A Crafting Input Buffer line without a stack is an emptied
  slot. The line's player is the player whose GUI click or Interface Terminal action changed the slot. A line for
  another change doesn't have a player.

## Storage

- `container_session` lines are the GUIs that a player had open, other than the player's inventory, such as chests,
  machines and crafting tables. When the GUI closes, the mod compares the item counts by key with the counts at the
  open: `containerDelta` for the GUI's slots outside the player's inventory, and `playerDelta` for the player's
  inventory (main, armour and cursor). The mod writes a line when `playerDelta` has an entry, and for each GUI of a
  creative player. `pos` is the block of the first tile entity among the GUI's slots. For a GUI without a tile entity
  slot, such as a double chest or a ModularUI machine, `pos` is the block that the player right-clicked within 20 ticks
  before the open. Items that a machine makes or uses while the GUI is open count in `containerDelta` only. Items picked
  up from the ground while a GUI is open count in `playerDelta`. A creative player's GUI also gets `container_open`, and
  each click in that GUI gets a `container_click` line with the changed slots and the cursor. `/inv view` has separate
  lines.
- `trash_insert` lines are stacks that a player put into an Extra Utilities trash can by hand. The trash can voids the
  stacks at once. The mod doesn't log inserts by pipes and hoppers. `trash_can_closed` lines are the contents of a
  ServerUtilities `/trash_can` at the close, which destroys the contents.
- `/inv view` (ServerUtilities) is logged per view. An `invsee_open` line starts a view. The line has a `viewId`,
  whether the target was online, and the other open views of the same target (`concurrentViews`). `invsee_click` lines
  are single clicks that changed a slot. For each changed slot, a line has `owner` (`viewer` or `target`), the
  inventory, the index, and the stacks before and after. The target's moves during the view aren't in these lines.
  `invsee_close` has how the view ended (`how`: `closed`, `replaced` by a new `/inv view` without a close, or
  `viewerLogout`), the target's joins and leaves during the view, `overlappingViews`, and an `outcome`.
- The `outcome` follows ServerUtilities' rules. An offline target's inventories are a copy, and only a normal close
  while the target is offline writes the copy to the target's file. When the target is online at the close, or the view
  ends without a close, ServerUtilities skips the write to the target's file. The items that the viewer took then exist
  twice, and the items that the viewer put in are lost (`changes lost …`). A view of an online target edits the live
  inventories until the target logs out. Later clicks go to a player object that ServerUtilities doesn't save (`changes
  after target logout lost`). Two views of one offline target each save a copy, and the view closed last overwrites the
  other (`saved, last writer wins`). When ServerUtilities saves a view after the target logged in and out again,
  ServerUtilities overwrites the target's session in between. The Backpack mod and Adventure Backpack tabs save by other
  rules, and the outcome doesn't cover them.
- `storage_census` files (hourly) have the EnderStorage item inventories by frequency and owner, and the Backpack mod's
  backpack files. A backpack file is the Backpack mod's last save of the backpack.

## Placements

- `block_placed` lines are player placements of the server's `trackedPlacements` blocks. Each line has the coordinates.
  The default list is the Stargate structure: base, ring and chevron, DHD and power units. GT machines log as
  `machine_placed`. SUMMARY has the first placement of each block, and the JSONL has each placement.
- A `machine_placed` line's `itemKey` and `displayName` are the item in the player's main hand. That item is a tool when
  a Vajra swaps in the offhand block (`gregtech:gt.Tool_Vajra`), or when a StructureLib hologram projector builds from
  the inventory (`structurelib:item.structurelib.constructableTrigger`). `machineId` (the meta tile ID) and `machine`
  name the GT machine read from the world. Lines before v0.7.0 don't have the two.
- `bulk_placed` lines are GT machines that a Matter Manipulator placed. The mod writes one line per player, dimension,
  manipulator and tick. A line has `machines` and `pipes` as `[x, y, z, meta tile ID]` entries, `names` by meta tile ID,
  and `checked`. `checked` is the count of spots that the manipulator went over that already had that machine. Before
  v0.6.0, the manipulator's `machine_placed` lines (`itemKey` `matter-manipulator:…`) are those spots with an existing
  machine, and the mod doesn't log what the manipulator placed.
- `machine_removed` lines are GT machines, hatches, pipes and cables that left the world. Each block gets one line. A
  line has the `machineId`, `machine` and `kind` fields of `machine_placed`. `cause` is one of these:
  - `broken` or `wrenched`: a player broke the block with the tool in hand (`uuid`, `name`, `tool`)
  - `vajra`: a player broke or right-clicked the block with GT's or Gravitation Suite's Vajra (`uuid`, `name`, `tool`)
  - `exploded`: the machine's explosion, or another explosion over the block
  - `burned`: a GT cable or pipe set on fire
  - `other`: a command, a WorldEdit edit, or another mod's code

  A line with `kind` `unknown` didn't have a GT tile at the moment of removal. Before v1.0.0, the mod doesn't log
  removals, so a spot can have two placements without a removal between them.
- `bulk_removed` lines are GT machines that a Matter Manipulator removed or replaced. The lines have the fields of
  `bulk_placed`, except `checked`.
- A GT machine's front facing, such as `NORTH`, is `facing` on `machine_placed`, and the fifth value of a `bulk_placed`
  machine entry: `[x, y, z, meta tile ID, facing]`. A pipe doesn't have a facing. A `machine_facing` line is a turn to a
  new facing after the machine's first second, such as a wrench rotation. The line has the machine's place, `machineId`,
  `machine`, `machineKey`, `from` and `to`. The mod doesn't log a line for the facing that a world load reads from the
  save. Before v1.1.0, only a multiblock controller's footprint has a facing.
- `machineKey` (on `machine_placed` and `machine_removed`) and `keys` (on `bulk_placed` and `bulk_removed`) are GT's
  unlocalized machine keys, such as `gt.blockmachines.hull.bronze`. Use the keys to compare runs. `machine` and
  `displayName` are in the server's language, and in singleplayer that language is the player's client language.

## Magic

- `watched_block` lines are player places and breaks of the blocks on the `watchedBlocks` list: Thaumcraft nodes, node
  jars, stabilizers and the transducer, Ethereal Shards, Blood Magic altars and ritual stones. A break has the tile's
  NBT. Jarring a node with a wand doesn't fire an event, so compare `node_census` files. In v0.6.0 and earlier, a place
  line's `meta` is the meta of the replaced block (Forge's `PlaceEvent.blockMetadata`), so the mod matched placements of
  the meta-specific entries (`blockAiry@0`/`@5`, `blockJar@2`, `blockStoneDevice@9..11`) against the replaced block, and
  usually missed them. Use the `node_census` files for those runs.
- `node_census` files (`nodes-<ticks>.json.gz`, every 15 minutes and on `/audit snapshot`) have the full NBT of each
  Thaumcraft node, node jar and Ethereal Shard in loaded chunks.
- `blood_altar_tier` lines are altar tier changes. The altar checks the tier on use and on a timer, and the mod logs a
  change at the next check.

## Power

- A `power_census` file (`power-<ticks>.json.gz`, every 5 minutes and on `/audit snapshot`) has each Lapotronic
  Supercapacitor in loaded chunks: stored EU, capacity, the capacitors installed per tier, wireless mode and owner. A
  file also has each team's wireless EU. EU values are decimal strings. Capacitor counts come from the LSC's last
  structure check, and are valid only when `formed` is true. The log line has the totals (`lscStored`, `wirelessStored`)
  and `readErrors`. A census doesn't have an entry for an LSC in an unloaded chunk.
- `power_use` files (`power-use-<ticks>.json.gz`, every 5 minutes, on `/audit snapshot` and at a clean stop) list the EU
  that GT machines consumed since the previous `power_use` line: `types` per machine type, `multis` per multiblock
  controller with the controller's position, and `recipes` per machine type and first output (`starts`, `outputAmount`,
  `eu`). The log line has `fromTick`, `toTick`, the total `eu` and `wirelessEu`, and `readErrors`. Each `eu` includes
  the `wirelessEu` of the same entry.
- A machine's EU is the EU drained from the energy hatches, the machine's buffer and the wireless network, less the EU
  moved into the machine's buffer. A recipe's EU is EU per tick times duration. The mod counts the EU at the recipe
  start, so a recipe that power-fails counts in full. The mod doesn't count non-GT machines, the Space Elevator modules,
  the Nanochip Assembly Complex and the Lapotronic Supercapacitor. Those three change their EU field directly. The
  Active Transformer and the Power Substation have the EU that they send to their dynamo hatches. An interval without
  consumed EU or a started recipe doesn't get a line.
- `gt_powerfail` lines are multiblocks that stopped for lack of power, the data behind GT's `/powerfails`.

## Edits

- `nbt_edit` lines are `/nbtedit` saves. `nbtB64` is what the save wrote, and `beforeB64` is the target just before the
  save, so the two have what the edit changed. Above 64 KB, the line has only the hashes (`nbtHash`, `beforeHash`).
  `beforeMissing` has the reason for a missing before state (`chunk not loaded`, `no tile entity`, `empty hand`, …).
  `beforeMissing: no pending edit` is a save packet from a sender without an open `/nbtedit`. ServerUtilities skips such
  a packet, and only a modified client sends one. Bundles before v0.6.0 don't have the before fields. A block opened
  with `/nbtedit` and never saved has only the command line.
- `worldedit_edit` lines are the changes of each WorldEdit edit by a command, a brush, a wand tool or `//undo`. The mod
  writes one line per edit session per tick. `placed` and `removed` are `block@meta` counts of the blocks that changed,
  `min` and `max` are the bounds, and `tileEntities` has the first 256 blocks written with tile data. Each entry is `[x,
  y, z, block@meta, tile id, GT meta tile ID?]`. `tileEntitiesTotal` is the exact count. An edit without a player, such
  as a script without an actor, doesn't have `uuid` or `name`. The `command` line in the same tick has the text. The mod
  logs these lines only when the server runs WorldEdit, and the pack doesn't include WorldEdit.

## Quests

- The mod logs `quest_db` at each start. The line has the hash of the quest book file on disk, and the hash of the live
  quest database.
- `quest_edit` lines are BetterQuesting edit packets (quest edits, chapter edits, imports, and settings such as edit
  mode). A line has the sender and a payload hash. Refused attempts by players who aren't ops are logged too.
- `quest_task_complete` lines have the `actor`, the party member through whom BetterQuesting detected the task's
  completion, beside each member who gets credit for the task.

## Milestones

- `dim_change` lines have each dimension change: `fromDim`, `toDim` and the arrival position. Bundles before v0.6.0 have
  only `dim_first_visit`, and the mod logs a `dim_first_visit` again after a world rollback.
- `achKey` on `achievement` is the achievement's lang key, and `provider` on `dim_first_visit` is the dimension's
  WorldProvider class. Use these to compare runs. `achName` and `dimName` are in the server's language.

## Prospecting

- `prospect` lines are ore prospecting. `tool` is `prospector_scanner` (a click on a block with a Prospector's Scanner,
  electric or not), `prospecting_hammer`, `adv_seismic_prospector` or `electric_prospector` (the scanner's map).
- A chat tool's line has `itemKey`, the clicked block, and the chat `lines` that the player received during the use. The
  mod keeps up to 64 lines or 4 KB, and then sets `truncated`.
- A map's line has the centre `x`, `z`, `chunkX` and `chunkZ`, the `radius` in chunks, and `mode`. Mode 0 is big ores, 1
  is all ores, 2 is fluids and 3 is pollution.
- The ore `veins` and `fluids` that VisualProspecting received for the player in the same tick are on the same line. A
  line with `tool` `visualprospecting` has such results without a tool scan in the same tick.
- An Advanced Seismic Prospector line has `tier`, `radius`, and the `ores` and `oils` written to the data stick.
- On GT 5.09.52 and later, GT sends the scanner map ticks after the right-click, and a scan cancelled by a new
  right-click doesn't get a line.

## Positions

- `pos_sample` lines have the place of each online player. By default, the mod samples every 30 s in survival and every
  5 s in another mode (creative, adventure, spectator). The config keys are `positionSampleSeconds` and
  `positionSampleSecondsOutsideSurvival`. A player gets the shorter interval within 5 s of leaving survival. A line has
  a row in `players` for each player sampled on that tick:

  ```
  [player, mode, dim, x, y, z, yaw, pitch]
  [0,"s",0,1335,1050,-2150,-160,30]
  ```

  - `player` is the `playerNumber` of the player's `player_join` line in the same session. The first player to join is
    0, and the next is 1. A player who rejoins keeps the number.
  - `mode` is `s` (survival), `c` (creative), `a` (adventure), an empty string for a player without a gamemode, or the
    full name of another mode, such as `spectator`.
  - `x`, `y` and `z` are the feet, in tenths of a block: 1335 is x = 133.5.
  - `yaw` is in whole degrees from -180 to 179: 0 is south (+z), 90 is west, -90 is east and -180 is north. `pitch` is
    from -90 (up) to 90 (down).

  An online player without a row stayed still. Such a player is in the dimension and mode of the player's last row. The
  player is within 1 block of that row on each axis, and within 10 degrees of that row's yaw and pitch. The first sample
  after each join has a row. A row is the player's place on that tick only, and between samples the player can go
  anywhere. The `player_join` and `player_leave` lines have who was online. Bundles before v1.0.0 don't have samples.
- `teleport` lines are ServerUtilities teleports of a player. `type` is `HOME`, `WARP`, `SPAWN`, `BACK`, `TPA` (the
  requester that `/tpaccept` moved), `RTP`, `VANILLA_TP` (the admin teleport of the claimed-chunks screen) or `TPL`
  (`/tpl`). `warmupSeconds` is the warmup that the player waited, so the `command` line is that many seconds earlier.
  The mod writes the line just before the move, so for a move to another dimension the `teleport` line comes before the
  `dim_change` line. `from` has `dim`, `pos`, `yaw` and `pitch`, and `to` has `dim` and `pos`. ServerUtilities puts the
  player's feet at that spot, and doesn't change the facing. A teleport cancelled by a move or a hit during the warmup
  has a `command` line, and doesn't have a `teleport` line.
- A player that another command moved, such as vanilla `/tp` or `/cofh tpx`, gets a `teleport` line with `type`
  `COMMAND`, the command's name in `command` and the command's sender in `by`. The mod reads each online player's place
  before and after the command, and a player in another dimension or more than 0.5 block away afterwards was moved. The
  mod writes that line after the move, so for a move to another dimension the `teleport` line comes after the
  `dim_change` line. A teleport that finishes after the command returns, such as a ServerUtilities warmup, isn't a
  `COMMAND` teleport. Bundles before v1.0.0 don't have `teleport` lines.
- The pack turns `/home`, `/back`, `/warp`, `/spawn`, `/tpa` and `/rtp` off in ServerUtilities' config. A runner who
  turns them on gets their lines.

## Multiblocks

- GT `multiblock_formed` lines have `energyHatches` and `energyTier`, and `dynamoHatches` and `dynamoTier`: the count of
  hatches of the structure at the formation, and the highest voltage tier among them (`LV` … `MAX+`). The tier comes
  from the voltage of each hatch. The mod logs one line when a GT multiblock forms, and another when the hatch tier
  changes, so a hatch upgrade is a new line. The controller saves the logged tiers in the controller's NBT, so the mod
  doesn't log a new line after a server restart. After a restore of a backup from before the formation, the mod logs the
  formation again. Before v0.6.0, each formed multiblock logs a line once per session. Some TecTech and GT++ multiblocks
  keep hatches in separate lists, and can report fewer hatches or a missing tier. Railcraft multiblocks and the Bricked
  Blast Furnace don't have hatch fields.
- GT `multiblock_formed` lines have the footprint too. `bounds` is `[minX, minY, minZ, maxX, maxY, maxZ]`, the inclusive
  world coordinates of the block positions that the StructureLib structure check visited, and the controller. A probe
  past the end of a multiblock of variable length, such as the Assembly Line's next slice or the LSC's next layer,
  fails, and isn't in `bounds`. `facing` is the controller's front, and `rotation` and `flip` are StructureLib's, absent
  for a controller without a StructureLib check. Multiblocks that check by hand (Cleanroom, Charcoal Pit, LESU) don't
  have `bounds`. Railcraft multiblocks and the Bricked Blast Furnace have neither.
- When the footprint of a formed multiblock changes without a new formation, such as after a wrench rotation or when an
  LSC grows layers, the mod logs a `multiblock_footprint` line with the same fields. The controller saves the last
  logged footprint, so after an update to v0.8.0 or later, the mod logs one `multiblock_footprint` line for each GT
  multiblock at the multiblock's first check, and for each TecTech multiblock at the next re-check. The mod then doesn't
  log another line for the multiblock until the footprint changes.
- A line with `bounds` also has the blocks of the multiblock. `extentPalette` is a list of block keys, and `extents` has
  one cell for each block position of the `bounds` box. A cell is an unsigned 16-bit little-endian value: 0 for an empty
  position, and otherwise the index in `extentPalette` plus 1. Cell `((y - minY) * sizeZ + (z - minZ)) * sizeX + (x -
  minX)` is the block at (x, y, z). `extents` is the zlib-compressed cells in base64:

  ```python
  cells = np.frombuffer(zlib.decompress(base64.b64decode(extents)), '<u2').reshape(sizeY, sizeZ, sizeX)
  ```

  The cells are the positions that the structure check visited, and the controller. Each cell has the block that was in
  the world when the mod wrote the line. A cell with air is 0, so the positions that must be empty are 0. A GT machine
  (controller, hatch, bus) has the `machineKey` of `machine_placed`, and another block has `modid:name@meta`.
  `extentBlocks` counts the cells with a block. The footprint key includes the visited positions, so the mod logs a
  `multiblock_footprint` line for a change of shape inside the same box. The mod doesn't log a line for a block swapped
  in place, such as a coil upgrade. A box of more than 4,194,304 cells, or a check that visited more than 1,048,576
  positions, doesn't have `extents`. Bundles before v1.0.0 don't have `extents`. After an update to v1.0.0 or later, the
  mod logs one `multiblock_footprint` line at the first check of each GT multiblock with a StructureLib check.
- `multiblock_formed` lines from GT controllers can have `structure`: the coil level (`HeatingCoilLevel` fields),
  `mHeatingCapacity`, and the integer `…Tier` fields of the controller class, such as glass, casing and motor tiers. The
  mod logs the formation again when any of these values changes. `structureUnreadable` lists the controller classes
  whose tier fields the server couldn't read, so `structure` is incomplete for that line. Only a server on Java 8 should
  have the field, for a class with a field whose type is client-only, such as the Extreme Entity Crusher or the BEC
  storage. Newer Java reads those fields by name.
- `multiblock_log_failed` lines are formations or footprint changes that the mod failed to record. INNOCENCE.md has the
  causes.
