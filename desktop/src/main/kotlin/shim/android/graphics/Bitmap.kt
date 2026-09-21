package android.graphics

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.imageio.ImageIO

/**
 * Desktop stand-in for `android.graphics.Bitmap`, backed by an AWT image.
 *
 * Aniyomi's `AnimeHttpSource.getImageTile` hands a Bitmap to the app to show as
 * a thumbnail tile; nothing on the desktop consumes the tile, but the method is
 * part of the source API an extension's bytecode links against, so the type has
 * to exist and decode real bytes.
 */
class Bitmap(val image: BufferedImage) {
    // Android's API is the METHODS (Kotlin extension code reading
    // `bitmap.width` compiles to a `getWidth()` call), so they are declared as
    // functions rather than properties — a Kotlin `val width` would generate
    // getWidth() and clash with them.
    fun getWidth(): Int = image.width
    fun getHeight(): Int = image.height
    fun isRecycled(): Boolean = false
    fun recycle() {}
}

object BitmapFactory {

    @JvmStatic
    fun decodeStream(inputStream: InputStream): Bitmap? =
        runCatching { ImageIO.read(inputStream) }.getOrNull()?.let { Bitmap(it) }

    @JvmStatic
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int): Bitmap? =
        runCatching { ImageIO.read(ByteArrayInputStream(data, offset, length)) }
            .getOrNull()?.let { Bitmap(it) }
}
