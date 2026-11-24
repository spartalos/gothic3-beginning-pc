# Audio (javax.microedition.media)

Pure-JDK implementation in `midp/src/main/java/javax/microedition/media/` (API) and `.../impl/` (engine).

- The game loads `/NN.mid` (`audio/midi`) and `/NN.amr` (`audio/amr`) with `getResourceAsStream`, calls
  `Manager.createPlayer(stream, type)`, then `realize()`, `prefetch()`, `addPlayerListener(MIDlet)`; plays with
  `setLoopCount(1)` + `start()`. Its `playerUpdate` compares events with `==` against `"stopped"`/`"endOfMedia"`,
  so only the interned `PlayerListener` constants are ever passed. Callbacks run on the `media-events` thread.
- MIDI: one private `Sequencer` per prefetched player, all feeding one shared Gervill `Synthesizer`
  (opened lazily). Stop sends all-notes-off.
- MIDI SoundFont: without a bank Gervill falls back to its generated "emergency" set, which sounds crude. `MidiBackend` therefore loads a SoundFont when it
  opens the shared synthesizer (never with `--no-audio`: that uses silent players and never touches the synthesizer). `SoundbankLocator` picks the first of:
  1. `-Dg3.soundfont=<file.sf2 or folder>`, which the launcher fills from the config key `audio.soundfont` (in `config.properties`; the system property wins);
  2. `soundfont.sf2`, else the first `*.sf2` of the `soundfont/` folder, else any `*.sf2`, next to the app jar (`<app>/soundfont/` in the app images);
  3. the JDK default bank (current behaviour before this feature).

  A missing or unreadable file logs a line and falls back. Which bank is in use is logged once on stderr (`[audio] MIDI soundbank: ...`). The swap is
  `open()` (which always loads the default bank), `unloadAllInstruments(default)`, `loadAllInstruments(sf2)`; loading before `open()` is silently discarded by
  Gervill. Loading GeneralUser GS (32 MB) costs about 1 s on first music start (on the `media-audio` thread, `open` allows 5 s). The game's bank selects
  (CC0 = 122 for melodic channels, 123 for the drum channel, CC32 = 0, GM program numbers) all resolve to the normal GM instruments with GeneralUser GS (checked
  by offline rendering, every channel of every `NN.mid` is non-silent). Gervill's quality knobs (`interpolation`, `max polyphony`, `large mode`) are only
  reachable through the internal `com.sun.media.sound.AudioSynthesizer`, which the app does not use, so the defaults stay.
  The release workflow downloads GeneralUser GS v2.0.3 (S. Christian Collins, free licence incl. redistribution; pinned commit and SHA-256 in
  `scripts/fetch-soundfont.sh`) into every app image (`soundfont/GeneralUser-GS.sf2` + licence text) and attaches it to the release next to the fat jar. The `.sf2` is
  never committed (gitignored). To use your own bank: drop a General MIDI `.sf2` into the app's `soundfont/` folder (or next to the jar), or set `audio.soundfont`
  to its path; delete/rename the bundled one to go back to the JDK bank. Local packaging with it: `scripts/fetch-soundfont.sh /some/dir`, then
  `./gradlew :desktop:packageApp -PsoundfontDir=/some/dir` (also env `G3_SOUNDFONT_DIR`). Dev runs: `scripts/fetch-soundfont.sh soundfont-out`
  (gitignored); `./gradlew :desktop:run` then passes `-Dg3.soundfont=soundfont-out` unless you give your own `-Dg3.soundfont`.
- MIDI music is played from pre-rendered PCM (`PcmMidiBackend`, `MidiRenderer`, `AudioSink`), not synthesised in real time. Reason: the live path (a `Sequencer`
  per player feeding Gervill's real-time render thread, 120 ms default line buffer) starves whenever the machine is busy (map loads, fights: GC, CPU spikes), and the
  music stutters; this happened with the JDK default bank too, so it is not the SoundFont. Now each MIDI is rendered offline once (Gervill `SoftSynthesizer.openStream`,
  44.1 kHz stereo 16 bit, the sequence's tempo map applied, + 2 s tail) on a low-priority background thread (`media-midi-render`) when the player is created, cached in
  memory (soft refs) and on disk (`<data dir>/gothic3-pc/cache/music/<sha1(midi + soundfont name/size/mtime + rate + version)>.pcm`, override `-Dg3.audio.cache=DIR`),
  and played through a `SourceDataLine` with a 400 ms buffer (`-Dg3.audio.bufferMs`) fed by a max-priority thread that only copies bytes (no allocation in the loop).
  The feeder can therefore be starved up to 400 ms (live: 120 ms) and nothing else is real-time work. Until a track's render is ready (first run only: 0.4-2.4 s per track
  with the SoundFont, 0.04-1.3 s default bank) that player uses the live path; the choice is made at prefetch/start time.
  Player semantics are kept: `start/stop/close`, `setLoopCount` (whole sequence, tick 0 to the last tick, like the live `Sequencer` loop; -1 = forever; the tail of a pass is
  mixed over the start of the next so loops are seamless), `getMediaTime` (frames played by the line, within the pass) / `setMediaTime` (applied by the next start), duration,
  `endOfMedia` (fired when the last music frame has been played; the tail rings out), `VolumeControl`/master volume (linear gain applied while copying, so a change is heard
  after up to the buffer length, 0.4 s; the live path used a master-volume SysEx), stop = 30 ms fade (no click). The SFX gate and `--no-audio` are unaffected.
  Offline rendering needs the non-exported `com.sun.media.sound` (reflection): the launcher scripts, `gradle run` and the app image pass
  `--add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED`, the fat jar has `Add-Exports` in its manifest. Without it (or if the render fails) the live path stays in use.
  Memory: all nine tracks are 487 s of music = 86 MB of PCM, longest 26 MB (03.mid); only players that are open hold their data (the rest is a soft cache / the disk file).
  Debug: `-Dg3.audio.livemidi=true` forces the old live path; `-Dg3.audio.nullLine=true` replaces every MIDI output line with a silent real-time simulation that counts
  underruns (the live synth is then pumped into a 120 ms simulated line from a max-priority thread, like Gervill does; PCM goes to a 400 ms one) and prints
  `[audio] nullLine(pcm|live) closed: underruns=N worstGapMs=.. minAheadMs=..` at exit. Use it with `--no-sfx` (effects still use real clips) for measurements without sound.
  Measurements (12-core machine, `scripts`-style run: walk-ticks boot + 24 map warps, ~2500 ticks, nullLine, no sound; "underruns" = times the simulated device ran dry):
  live synth, default bank: 44 underruns, worst gap 315 ms (taskset 2 CPUs: 31 / 164 ms); live, GeneralUser GS: 56 / 329 ms (2 CPUs: 18 / 129 ms);
  pre-rendered PCM: 0 underruns on every line, default bank all CPUs and 2 CPUs, and GeneralUser GS (warm cache). G1 pauses in these runs are 12-19 ms (about 2700 per run), too short
  to explain 300 ms gaps alone: the cause is the real-time synth thread being starved by the load, which the PCM path removes. Not measured: audible result (nobody listened), the real device's own underrun behaviour.
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

## Music only (sound effects off)

F10 > Audio > "Sound effects" (`audio.sfx`, default on; `--no-sfx` or `-Dg3.sfx.off=true` forces it off for the run) switches the effects off live while the
background music keeps playing. It lives in the MIDP media layer only: every AMR (effect) player is wrapped in `SfxGateBackend`, which at each `play()` uses the
real clip backend when effects are on and a `SilentBackend` timer when off (`PlayerFactory.setSfxEnabled`). Player states, positions and `endOfMedia` events are
identical either way, so game logic and the determinism trace do not change. An effect already playing when the toggle is switched off finishes; later ones are silent.
With `-Dg3.media.debug=true` each effect start logs `[media] sfx play via audio` or `via MUTED (sound effects off)`.
