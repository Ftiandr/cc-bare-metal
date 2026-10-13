package dev.ccbaremetal.unicode;

/** Switch for Unicode on CC: Tweaked's computers (client config cc_tweaked.unicode; on by default and on servers). */
public final class UnicodeConfig {
    private UnicodeConfig() {
    }

    public static boolean enabled() {
        return dev.ccbaremetal.BareMetalClientConfig.ccUnicode();
    }
}
