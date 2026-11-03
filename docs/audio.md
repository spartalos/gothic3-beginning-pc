# Audio (javax.microedition.media)

Pure-JDK implementation in `midp/src/main/java/javax/microedition/media/` (API) and `.../impl/` (engine).

- The game loads `/NN.mid` (`audio/midi`) and `/NN.amr` (`audio/amr`) with `getResourceAsStream`, calls
  `Manager.createPlayer(stream, type)`, then `realize()`, `prefetch()`, `addPlayerListener(MIDlet)`; plays with
  `setLoopCount(1)` + `start()`. Its `playerUpdate` compares events with `==` against `"stopped"`/`"endOfMedia"`,
  so only the interned `PlayerListener` constants are ever passed. Callbacks run on the `media-events` thread.
- MIDI: one private `Sequencer` per prefetched player, all feeding one shared Gervill `Synthesizer`
  (opened lazily). Stop sends all-notes-off.
- AMR: the JDK cannot decode it. `AmrConverter` (buildtools) converts each `.amr` with ffmpeg
  (OpenCORE decoder; ffmpeg's native one fails on 6.7 kbps / SID frames; the app images bundle a minimal ffmpeg, see docs/byo-jar.md "Audio") to 22050 Hz mono WAV in
  `game/gen/resources/audio/` and writes `index.properties` (`sha1(original amr bytes)=audio/NN.wav`).
  At runtime `createPlayer` hashes the bytes it was given and plays the WAV through a preloaded `Clip`.
  Unknown AMR data logs a warning and yields a silent player with correct timing.
- No audio device / synth: the player falls back to a silent timer; state and events stay correct.

## Music and sound effects together (port change)

The original's Sound menu is a mutually exclusive radio group "No sound" / "Background music" / "Sound effects" (`Gothic3Midlet.applyOption` ->
`setSetting`: 1 = music only, 2 = effects only, 0 = off, 5 = both; no original menu entry could set 5). On a phone that meant the startup answer
"Activate sound?" -> Yes gave music only, and the 25 `.amr` effects (ids 9-33) were silent unless one picked "Sound effects" (which turned the music off).
The port makes music and effects play together (`// PORT:` edits in `Gothic3Midlet` / `GameEngine`, patch set `game-patches/g3`):

* The "Background music" entry now selects setting 5 (`applyOption(1)` -> `setSetting(5, 1)`), its checkbox is checked for setting 1 or 5 (`getOptionState`), and
  its label reads "Background music + SFX" (string id 400 of `EN.MAIN.lng`, relabelled when the language file is parsed; the menu layout and font are unchanged).
  "No sound" and "Sound effects" (effects only) are unchanged. "Activate sound?" -> Yes (menu action 9) also sets setting 5.
* **Existing settings**: the settings record (RMS slot 0, byte[7], NOT part of the savegame slots 2/3) of an older run holds `[1] = 1` for "Background music".
  `loadSettings` maps `[1] == 1` to music + effects (`[1]=0, [2]=0, [5]=1`) in memory; the record is rewritten only on the next `saveSettings`, and old records stay
  loadable by every earlier build's logic. Savegames are untouched. Effects-only and off records are unchanged. (`pauseGame` also treats setting 5 like music mode
  and suspends/resumes the sounds instead of stopping them.)
* **Priority logic** (`startSound`, `stopLowerPrioritySounds`, `stopSoundOrFlagStopped`, `requestSound`/`playPendingSound`). `startSound` is mangled in the decompile but
  the bytecode (`HG.K(int)`) is the same: it sets the "blocking priority" to `Integer.MAX_VALUE` as soon as ANY sound is in state 0 (playing), so nothing could ever
  start while the looping music played. Priority-5/9 effects would also have stopped the music (`stopLowerPrioritySounds`; music has priority 0 and no "same category
  only" flag), and with `soundDeferred` (always true) `stopSoundOrFlagStopped` calls `stopAllSounds()`. In setting 5 (only) these now look at sounds of the same
  category only (music = flag bit 3, else effect): effects do not block or stop the music and the music does not block effects, while effect vs effect and music vs music
  behave as before. Music additionally gets its own pending-sound slot (`StartupPrefs.pendingMusicId`, outside the g3 statics so the state hash does not gain a field), so
  that e.g. an NPC death sound requested in the same tick as the restarted ambient music cannot drop the music (one pending sound per tick, first of equal priority wins).
  Settings 1, 2 and 0 take exactly the old paths.
* Output paths: MIDI music (Gervill `Sequencer`/`Synthesizer`) and the WAV effects (`Clip`) are separate `Backend`s with their own lines and do not interfere; each AMR
  effect is its own `Clip` player.
* Determinism: the tick trace hashes all static fields of the g3 classes, including `settings`, so with the new default answer (setting 5 instead of 1) the hashes of
  `scripts/walk-ticks.script` differ from before. With `--trace-fields` the only g3 fields that differ are `settings` (value 5 vs 1), `langStrings` (the relabelled string),
  and the sound bookkeeping `soundState` / `soundPlayers` / `soundSlots` / `pendingSoundId` (effects now run); no gameplay field differs.

`-Dg3.media.debug=true` logs each `createPlayer` (type, size) and each player start/stop/end/close with the number of started players per content type
(`start audio/amr playing={audio/midi=1, audio/amr=1}` shows effects playing over the music) on stderr; unknown AMR data and a missing
`audio/index.properties` always log a warning.
