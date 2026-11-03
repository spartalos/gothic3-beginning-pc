package gothic3.desktop.render3d;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import javax.microedition.lcdui.Image;

/** What the props package needs from the renderer: textures for images and the shared vertex layout. */
public interface TexSource {
    VertexAttributes ATTRS = QuadBuilder.ATTRS;
    Texture get(Image im);
}
