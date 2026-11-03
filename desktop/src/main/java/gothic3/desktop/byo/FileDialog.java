package gothic3.desktop.byo;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** Native open-file dialog through LWJGL's tinyfd (Win32 common dialog, macOS osascript, Linux zenity/kdialog). Blocking. */
final class FileDialog {
    private FileDialog() {}

    /** @return the chosen path, or null when cancelled / no dialog tool is available */
    static String pickJar(String startDir) {
        try (MemoryStack st = MemoryStack.stackPush()) {
            PointerBuffer filters = st.mallocPointer(1);
            filters.put(st.UTF8("*.jar")).flip();
            return TinyFileDialogs.tinyfd_openFileDialog("Select Gothic3-TheBeginning_s40v3a.jar", startDir, filters, "Java archive (*.jar)", false);
        }
    }
}
