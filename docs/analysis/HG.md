# HG (proposed: `Gothic3Midlet`)

MIDlet entry point (`MIDlet-1: Gothic3TheBeginning,icon.png,HG`) plus four "services" that live in static state:
resource/RMS IO, language/text, sound manager, and the whole text/menu UI (menus, inventory lists, stats, quest log,
save/load slots). Rendering of the game world is in A (the Canvas); HG only draws menu chrome via `A.*` helpers.
Mapping: `docs/mapping/HG.map`.

## Startup sequence
1. Constructor `HG()`: `cb = this`; creates canvas `bb = new A(this)`; `A.i = loadPalette()` (file `c`), `A.j = loadFonts()` (file `f`),
   `computeFontMetrics()` (-> `A.k[3]`), `Display.setCurrent(bb)`, starts the game-loop thread `A.e = new Thread(bb)`,
   `loadSettings()` (RMS slot 0), `bb.A(1)` (state 1 = loading screen).
2. State 1 spawns thread `C` (a Runnable) which calls `A.IA(1)`: `loadLanguageDef()` (file `ldf`), `selectLanguage(System.getProperty("microedition.locale"))`
   (loads the 5 `*.lng` files), `loadMoreInfoUrl()` (file `mi`, absent in this jar), reads save timestamps (`A.TB`), then `A.MA/RA`
   (graphics/data loading, files a/d/i...), and finally `initSounds()` (reports progress 75..100 via `C.c`).
3. `startApp()`, `commandAction()` are empty. `pauseApp()` and `A.hideNotify()` -> `A.WB()` -> suspend/stop sounds. `destroyApp` only calls `notifyDestroyed`.

## Timing / threads
- Game loop is in A.run(): fixed ~100 ms frame (`if (elapsed < 100) Thread.sleep(100 - elapsed)`) = max 10 fps, update (`A.UB`) then `C()` (logic) then paint + `flushGraphics`.
  HG itself has no loop. Every sound "request" made by game code is processed from the loop via `playPendingSound()` (`N()`) when `soundDeferred` (`ca`, default true).
- Thread `D` (HG.ba, "soundThread"): polls every 100 ms a queue (`D.a` Vector of sound ids), calls `requestSound(id)`/`findSoundIndex` for each; used for sounds with flag bit2 (async).
- Thread `C`: one-shot worker for loading/level generation (`A.s`), used by menu actions 1/30/39/100/102.

## Resource formats read by HG
All via `getResourceAsStream` (`openResource`).
- `c` (palette): `int32 BE n` (=30), then `n` x `int32` 0x00RRGGBB -> `A.i[]`. File in jar: n=30.
- `f` (fonts): `int16 count` (3), then per font `int16 flags`: bit0 -> FACE_PROPORTIONAL(32), bit2 -> FACE_MONOSPACE(64); bit4 -> SIZE_SMALL(8), bit5 -> SIZE_LARGE(16);
  style bit6 -> BOLD(1), bit7 -> ITALIC(2), bit8 -> UNDERLINED(4). -> `A.j[0..2]` (0 = large bold, 1 = default, 2 = small bold in this jar).
  `computeFontMetrics()` renders "(Fg)" in each font and measures to find glyph x/y offset and line height, packed in `A.k[i]` = `xoff | yoff<<8 | lineH<<16` (bytes).
- `ldf` (language definition): `byte ?`, `byte mode`, `byte len`, `len` bytes default lang (e.g. "EN"). Mode 0 = no strings, 1 = always use default lang, 2 = use locale string itself,
  3 = followed by `byte n` and n pairs of (len-prefixed locale, len-prefixed language) mapping. In the jar: `01 01 02 'E''N' 00` = fixed "EN".
- `/<LANG>.<NAME>.lng` for NAME in {MAIN, DIALOGUE_INITRIA, DIALOGUE_KHORINIS, DIALOGUE_MISC, QUESTS} (`langFileNames`): 
  `uint16 LE totalLen` (self-inclusive; body = totalLen-2 bytes, kept as `langRawBuf` n[]), then n[0] = encoding (0 = platform charset, 1 = UTF-16LE, 2 = modified UTF-8),
  n[1..2] = string count (LE16), then `count+1` LE16 offsets starting at n[3] (offset i..i+1 delimit string i, offsets relative to n[0]); `\r` is converted to `\n`.
  `langFileLoadMode` k[] = {1,2,2,2,2}: 1 = always loaded, 2 = loaded when `langFileWanted[]` set, 4 = on demand in `getText`. A.IA sets l[4]=true (QUESTS).
  String id for `getText(int)`: high 16 bits (bits 16..23) = file index, low 16 bits = string index (e.g. `I(65536+n)` = DIALOGUE_INITRIA, `I(262144|n)` = DIALOGUE_MISC / `A(short,short)` ORs 0x40000 for menu 25).
  `A` (around line 9680) parses the same files itself for dialogue (header 5 bytes then LE16 offsets, +2 for file position).
- `mi`: optional `uint16 BE len` + URL string, shown as an extra main-menu entry (text 388) that calls `platformRequest`. Not present in this jar.
- Sound files `/NN.mid|.amr` (`00.mid` .. `52.amr`): see below. Other files read directly by A/B (not HG): `a`, `d`, `i`, `*.png`, `*.off`, `*.mdl`.

## Sound system (static, in HG; thread D helps)
Table built by `buildSoundTable()` (`soundDefs` `s[i]` = 8 ints):
`{soundId, fileNo, extIndex, priority, flags, group, chainSoundId, size}`; rows whose file doesn't exist in the jar are dropped.
`SOUND_EXTENSIONS`/`SOUND_MIME_TYPES` index = extIndex (0 = .mid audio/midi, 1 = .amr audio/amr, ...). 34 defs: ids 0-6 = music tracks (00-06.mid, priority 0, flags 8),
7-8 = 10.mid/11.mid jingles (priority 6), 9..33 = .amr SFX/voice (20-52.amr).
Flags (`s[i][4]`): bit0 preload at init, bit1 keep player after stop (don't close), bit2 play through thread D queue, bit3 = is music (else SFX), bit4 = only preempt same category.
`group` (s[5]): sounds with equal group >= 0 are random variants (one picked by `randomBelow`). `chainSoundId` (s[6]) > 0: play that sound when this one ends (`onSoundEnded`);
music (bit3) loops by restarting a random member of its group at end-of-media.
Runtime arrays (size `soundCount`): `soundPlayers` Player objects, `soundSlots[i]={soundId,fileNo,playerIdx,_}` (players are shared if same file),
`soundState[i]`: -1 none, 0 playing, 1 stopped, 2 ended, 3 paused-by-suspend, 4 queued; `soundResumeIds` for resume after `suspendSounds`.
Settings (RMS slot 0, `settings` ab[7]): [0] derived "all off", [1] music only, [2] sfx only, [5] music+sfx (mutually exclusive: set via `setSetting`), [6] = a toggle (vibration likely), [3],[4] unused/unknown.
`isSettingOn(0)` = all of 1,2,5 off. `playSound` checks these. Priority arbitration: `stopLowerPrioritySounds` stops playing sounds with lower priority.
`playerUpdate` handles "stopped" / "endOfMedia" (note: compares strings with `==`, works only because of interning of the constants).
Retry logic: `loadSound` retries up to 3 times (`soundLoadRetries`), `startSound` up to 3 (`soundStartRetries`).

## Save / RMS
- RecordStore name `"RECSTR" + slot`; single record #1 holds the entire payload. `writeRecord` adds a 1-byte record first, requires `getSizeAvailable() >= 2*len`, returns 0 / -1 error / -2 no space (`lastRecordError`).
- Slot 0 = settings (7 bytes). Slots 2 and 3 = savegames (A.FA(slot) writes, A.GA(slot) reads, A.TB reads just the timestamps `A.ue` (slot3) / `A.ve` (slot2)); menu entries 373/374 label them. Slot 1 not used by HG code seen.
- Save payload via `saveOut` (DataOutputStream on `saveOutBytes`): `long System.currentTimeMillis()`, then int arrays each written as `short length` + `length` x `int32` (BE): 
  `A.ea` (player/global state), `A.pa`, `A.bA`, `A.cA`, `A.dA`, `A.eA`, `A.fA`, `A.gA`, `A.hA`, `A.iA`, then `A.fa[6][15]` (inventory categories, 90 arrays) then `A.aA[19]` (quest entries).
  Load mirrors it with `readIntArray()`. A.ea[11] == -1 means "no game started" and is patched to 0 and position A.ea[0] initialised on first save.
  (Meaning of the A arrays: see A mapping; ea[0] holds packed shorts: map/location x,y.)

## Menu / UI system (the bulk of HG)
Menus are `short[][] eA` (index = menu id `gA`), each a flat array of 4-short entries: `[textId, arg, type, extra]`.
Types: 1 title text, 2 / 3 right / left softkey (arg = action id, extra = icon frame, textId slot = icon sprite), 4 empty, 5 image (extra = frame), 6 frame/viewport marker,
50 plain wrapped text, 100 navigation button (`showMenu(arg)` after pushing to `menuStack`), 101 action button (`executeMenuAction(arg)`), 102 toggle option (`toggleStates`),
103/104 scrollable text block, 105 item row (inventory), 106/107 icon-button row (`iconButtonCount`), 108 stat list (`statLabels/statValues`), 109 icon+label button.
Selectable types are 100..199. `menuStack` holds (menu, selection) pairs for Back. Menu ids seen: 0 main, 1 "new game?" confirm, 13..16, 18, 19 inventory list (category `wa`), 20 item actions,
21 container/shop list, 22 buy/sell confirm, 23 character stats pages (`ka` 0..3), 24 item info, 25 quest log (`aA` 1/2 = active/finished), 26 quest detail, 14 save menu.
`executeMenuAction(id)` is the big action switch: 1/30/39 start/continue game (spawns thread C), 2 quit, 4/34 return to game (+autosave A.FA(2), starts music 1), 5 "more info" URL,
6/8/7 back / option change, 9/10 sound settings, 12/13/14 use/equip/drop item, 19/21/22 buy/sell/loot, 100-105 dialogue (script) choices.
A.b / A.UB key handling calls `selectNext/selectPrev/activateSelected`.

## Hints for other classes
- A: it is the `GameCanvas`/`Canvas` subclass. `A.run` = main loop at 100 ms/frame; `A.IA(int)` = async init (run on thread C); `A.WB` = suspend (pause/hideNotify), `A.VB` = resume;
  `A.i` = palette int[30], `A.j` = Font[3], `A.k` = font metrics int[3], `A.f/g` = screen width/height (sizeChanged), `A.e` = main thread, `A.b` running flag, `A.c` = paused flag, `A.u` = game state id,
  `A.FA(slot)`/`A.GA(slot)` = saveGame/loadGame, `A.HA(slot)` = saveExists, `A.TB` = readSaveTimestamps, `A.ue/ve` = save timestamps (slot 3/2), `A.s` = loader Runnable (class C), `A.fa[6][15]` = inventory,
  `A.aA[19]` = quests, `A.ea` = player stats (ea[11] = game-started marker, ea[0] packed (short,short) position), `A.ec[]` = sprite sheet frame table (indexes /5 and /11 divisions), `A.B(x,y,sheet,frame,g)` = drawSprite,
  `A.A(String,font,color,g)`-style text draw helpers, `A.aB` = current background/frame id for menu.
- B: `B.a[][]` = item/NPC definition table (int[] per entry), `B.F(int[])` = item name, `B.G(int[])` = item value/price, `B.u` = lookup of containers' first item.
- C: Runnable loader/worker (state: `c` = loading progress 0..100). D: sound queue Thread (see above); `D.A(id)` enqueue, `D.B(id)` -> `HG.L(id)`.

## Open questions
- Exact meaning of settings bytes ab[3], ab[4], ab[6] (6 probably vibration, unconfirmed); `soundTimestamps` x[] is initialised but never meaningfully used.
- Meaning of the 3rd field of `soundSlots` rows is "player index" (assumed); flag bit4 semantic only inferred from `stopLowerPrioritySounds`.
- Save slot 1 and the semantic split between slot 2 (autosave?) and slot 3 (manual?) not verified; check A callers of FA(2)/FA(3).
- Menu entry type 6 / 5 / 106-109 names are inferred from drawing code, not verified visually.
- Empty instance method `HG.D()` (called in ctor) probably a stripped platform hook; left unmapped.
