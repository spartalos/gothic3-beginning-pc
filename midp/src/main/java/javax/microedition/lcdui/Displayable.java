package javax.microedition.lcdui;

import java.util.ArrayList;
import java.util.List;

public abstract class Displayable {
    private String title;
    private CommandListener listener;
    private final List<Command> commands = new ArrayList<>();
    boolean shown;

    public void setTitle(String s) { title = s; }
    public String getTitle() { return title; }
    public boolean isShown() { return shown; }

    public void addCommand(Command cmd) {
        if (cmd == null) throw new NullPointerException();
        if (!commands.contains(cmd)) commands.add(cmd);
    }
    public void removeCommand(Command cmd) { commands.remove(cmd); }
    public void setCommandListener(CommandListener l) { listener = l; }
    CommandListener getCommandListener() { return listener; }

    public int getWidth() { return gothic3.platform.Platform.getWidth(); }
    public int getHeight() { return gothic3.platform.Platform.getHeight(); }

    protected void sizeChanged(int w, int h) {}
}
