package javax.microedition.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.microedition.media.impl.PlayerFactory;

public final class Manager {
    public static final String TONE_DEVICE_LOCATOR = "device://tone";

    private Manager() {
    }

    public static Player createPlayer(InputStream stream, String type) throws IOException, MediaException {
        if (stream == null) {
            throw new IllegalArgumentException("stream is null");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = stream.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        byte[] data = bos.toByteArray();
        if (data.length == 0) {
            throw new IOException("empty media stream");
        }
        return PlayerFactory.create(data, type);
    }

    public static Player createPlayer(String locator) throws IOException, MediaException {
        if (locator == null) {
            throw new IllegalArgumentException("locator is null");
        }
        throw new MediaException("Unsupported locator: " + locator);
    }

    public static String[] getSupportedContentTypes(String protocol) {
        if (protocol == null || protocol.equals("http") || protocol.equals("file")) {
            return new String[] {"audio/midi", "audio/sp-midi", "audio/amr", "audio/x-wav", "audio/wav"};
        }
        return new String[0];
    }

    public static String[] getSupportedProtocols(String contentType) {
        if (contentType == null) {
            return new String[] {"http", "file"};
        }
        for (String t : getSupportedContentTypes(null)) {
            if (t.equalsIgnoreCase(contentType)) {
                return new String[] {"http", "file"};
            }
        }
        return new String[0];
    }

    public static void playTone(int note, int duration, int volume) throws MediaException {
        if (note < 0 || note > 127 || duration <= 0) {
            throw new IllegalArgumentException("bad tone arguments");
        }
        // Tones are not used by the game; silently ignored.
    }
}
