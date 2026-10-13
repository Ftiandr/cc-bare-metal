package dev.ccbaremetal;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client configuration (config/ccbaremetal-client.toml). */
public final class BareMetalClientConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue TEXT_ZOOM;
    public static final ModConfigSpec.BooleanValue CC_UNICODE;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.comment("Computer screen").push("screen");
        TEXT_ZOOM = builder.comment("Size of the terminal font in screen pixels per font pixel; 0 picks one from the window size.",
                "Can be changed in the computer GUI with Ctrl+'+' / Ctrl+'-' / Ctrl+0 or Ctrl+mouse wheel.")
            .defineInRange("text_zoom", 0, 0, 8);
        builder.pop();
        builder.comment("CC: Tweaked's own computers, monitors, pocket computers and printouts").push("cc_tweaked");
        CC_UNICODE = builder.comment("Show UTF-8 text in our font and allow typing/pasting any character (sent as UTF-8).",
                "Programs still see bytes, exactly as without this; only drawing and input change.")
            .define("unicode", true);
        builder.pop();
        SPEC = builder.build();
    }

    private BareMetalClientConfig() {
    }

    public static int textZoom() {
        return SPEC.isLoaded() ? TEXT_ZOOM.get() : 0;
    }

    public static boolean ccUnicode() {
        return !SPEC.isLoaded() || CC_UNICODE.get();
    }

    public static void setTextZoom(int zoom) {
        if (!SPEC.isLoaded()) return;
        TEXT_ZOOM.set(zoom);
        SPEC.save();
    }
}
