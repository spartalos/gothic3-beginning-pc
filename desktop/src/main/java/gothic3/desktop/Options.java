package gothic3.desktop;

/** Command line / automation options. */
public final class Options {
    public String framesDir;
    public long frameEveryMs = 1000;
    public String script;
    public long exitAfterMs = -1;
    public int scale = -1;
    public boolean fullscreen;
    public int width = -1, height = -1;
    public String view;                // preset: wide | tight | classic | WxH (config screen.preset)
    public String midlet = "g3.Gothic3Midlet";
    public boolean noAudio;
    public String jar;                 // --jar <path>: the user's own Gothic3-TheBeginning_s40v3a.jar (docs/byo-jar.md)
    public boolean rebuildGame;        // --rebuild-game: discard the built game cache and build again
    public boolean windowFrames;
    // render / tick (see docs/platform.md "Render modes")
    public String renderView;          // 2d | 3d (default: config render.view); --view3d = 3d
    public String renderMode;          // classic | interpolated (default: config render.mode)
    public int maxFps = -1;            // 0 = follow display; -1 = config
    public String vsync;               // on | off (default: config window.vsync)
    public boolean hidden;             // create the window invisible (automation on machines without a usable compositor)
    public boolean overlay;            // start with the F3 overlay visible
    public String perfLog;             // "stderr" or file: one line per second with fps / tick rate
    // verification (deterministic tick tracing, see docs/platform.md "Automation")
    public String traceTicks;          // file: one line per logic tick with state/frame hashes
    public boolean traceFields;        // also per-field hashes (<file>.fields)
    public boolean deterministic;      // virtual game clock, synchronous loads, fixed RNG seed
    public String fixedSeed;
    public boolean verify;             // extra in-between renders per tick, parity + side-effect checks
    public String dumpTicks;           // comma separated tick numbers: dump canonical (and alpha=1) frames
    public String dumpDir;
    public String frameLog;            // per rendered world frame: tick, alpha, camera, player px
    public long exitAfterTicks = -1;
    // presented-frame burst capture
    public String burstDir;
    public int burstCount;
    public long burstAfterTick = 0;

    public static Options parse(String[] a) {
        Options o = new Options();
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--frames-dir": o.framesDir = a[++i]; break;
                case "--frame-every": o.frameEveryMs = Long.parseLong(a[++i]); break;
                case "--script": o.script = a[++i]; break;
                case "--exit-after": o.exitAfterMs = Long.parseLong(a[++i]); break;
                case "--scale": o.scale = Integer.parseInt(a[++i]); break;
                case "--fullscreen": o.fullscreen = true; break;
                case "--width": o.width = Integer.parseInt(a[++i]); break;
                case "--height": o.height = Integer.parseInt(a[++i]); break;
                case "--view": o.view = a[++i]; break;
                case "--midlet": o.midlet = a[++i]; break;
                case "--window-frames": o.windowFrames = true; break;
                case "--no-audio": o.noAudio = true; break;
                case "--jar": o.jar = a[++i]; break;
                case "--rebuild-game": o.rebuildGame = true; break;
                case "--render-mode": o.renderMode = a[++i]; break;
                case "--render-view": o.renderView = a[++i]; break;
                case "--view3d": o.renderView = "3d"; break;
                case "--max-fps": o.maxFps = Integer.parseInt(a[++i]); break;
                case "--vsync": o.vsync = a[++i]; break;
                case "--overlay": o.overlay = true; break;
                case "--hidden": o.hidden = true; break;
                case "--perf-log": o.perfLog = a[++i]; break;
                case "--trace-ticks": o.traceTicks = a[++i]; o.deterministic = true; break;
                case "--trace-fields": o.traceFields = true; break;
                case "--deterministic": o.deterministic = true; break;
                case "--fixed-seed": o.fixedSeed = a[++i]; break;
                case "--verify": o.verify = true; break;
                case "--dump-ticks": o.dumpTicks = a[++i]; break;
                case "--dump-dir": o.dumpDir = a[++i]; break;
                case "--frame-log": o.frameLog = a[++i]; break;
                case "--exit-after-ticks": o.exitAfterTicks = Long.parseLong(a[++i]); break;
                case "--burst": o.burstDir = a[++i]; o.burstCount = Integer.parseInt(a[++i]); break;
                case "--burst-after-tick": o.burstAfterTick = Long.parseLong(a[++i]); break;
                case "--help":
                    System.out.println("Options: --frames-dir <dir> --frame-every <ms> --script <file> --exit-after <ms>\n"
                            + "         --scale <n> --fullscreen --width <px> --height <px> --view wide|tight|classic|WxH --midlet <class> --no-audio\n"
                            + "         --jar <path to Gothic3-TheBeginning_s40v3a.jar> --rebuild-game\n"
                            + "         --view3d | --render-view 2d|3d (F5 toggles, F6 reloads render3d/tiles.properties)\n"
                            + "         --render-mode classic|interpolated --max-fps <n> --vsync on|off --overlay --perf-log stderr|<file>\n"
                            + "         --trace-ticks <file> [--trace-fields] --deterministic --fixed-seed <n> --verify\n"
                            + "         --dump-ticks 100,200 --dump-dir <d> --exit-after-ticks <n> --burst <dir> <count> [--burst-after-tick <n>]");
                    System.exit(0);
                    break;
                default:
                    System.err.println("Unknown option: " + a[i]);
                    System.exit(2);
            }
        }
        return o;
    }
}
