package dev.ccbaremetal.kernel;

import dev.ccbaremetal.kernel.fs.ImageBackend;

import java.io.IOException;
import java.io.UncheckedIOException;

/** The read-only system image (bin, lib, usr, default etc) bundled in the mod jar, loaded once and shared. */
public final class SystemImage {
    private static volatile ImageBackend image;

    private SystemImage() {
    }

    public static ImageBackend get() {
        var result = image;
        if (result != null) return result;
        synchronized (SystemImage.class) {
            if (image == null) {
                try (var stream = SystemImage.class.getResourceAsStream("/ccbaremetal/system.zip")) {
                    if (stream == null) throw new IOException("ccbaremetal/system.zip is missing from the mod jar");
                    image = ImageBackend.load(stream, System.currentTimeMillis() * 1_000_000L);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return image;
        }
    }
}
