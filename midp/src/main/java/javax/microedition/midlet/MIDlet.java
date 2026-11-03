package javax.microedition.midlet;

import gothic3.platform.Platform;

public abstract class MIDlet {
    protected MIDlet() {}

    protected abstract void startApp() throws MIDletStateChangeException;
    protected abstract void pauseApp();
    protected abstract void destroyApp(boolean unconditional) throws MIDletStateChangeException;

    public final void notifyDestroyed() { Platform.requestExit(); }
    public final void notifyPaused() {}
    public final void resumeRequest() {}

    public final String getAppProperty(String key) {
        if (key == null) throw new NullPointerException();
        return Platform.appProperties().get(key);
    }

    public final boolean platformRequest(String url) {
        Platform.log("platformRequest(" + url + ") ignored");
        return false;
    }

    public final int checkPermission(String permission) { return 1; }
}
