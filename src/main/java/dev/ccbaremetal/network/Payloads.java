package dev.ccbaremetal.network;

import dev.ccbaremetal.BareMetal;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Network messages. */
public final class Payloads {
    private Payloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BareMetal.MOD_ID, name));
    }

    /** Server to client: whole terminal (deflated {@code Terminal#snapshot}). */
    public record Terminal(int containerId, byte[] data) implements CustomPacketPayload {
        public static final Type<Terminal> TYPE = Payloads.type("terminal");
        public static final StreamCodec<RegistryFriendlyByteBuf, Terminal> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Terminal::containerId, ByteBufCodecs.byteArray(1 << 20), Terminal::data, Terminal::new);

        @Override
        public Type<Terminal> type() {
            return TYPE;
        }
    }

    /** Server to client: a program put text on the clipboard (OSC 52); only sent to players viewing its screen. */
    public record Clipboard(int containerId, String text) implements CustomPacketPayload {
        public static final Type<Clipboard> TYPE = Payloads.type("clipboard");
        public static final StreamCodec<RegistryFriendlyByteBuf, Clipboard> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Clipboard::containerId, ByteBufCodecs.stringUtf8(1 << 16), Clipboard::text, Clipboard::new);

        @Override
        public Type<Clipboard> type() {
            return TYPE;
        }
    }

    /** Server to client: framebuffer update for an open computer screen. */
    public record Framebuffer(int containerId, FbData data) implements CustomPacketPayload {
        public static final Type<Framebuffer> TYPE = Payloads.type("framebuffer");
        public static final StreamCodec<RegistryFriendlyByteBuf, Framebuffer> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Framebuffer::containerId, FbData.CODEC, Framebuffer::data, Framebuffer::new);

        @Override
        public Type<Framebuffer> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: power and display mode of an open computer, and whether the foreground program wants the
     * mouse (otherwise the GUI uses it for text selection and scrollback).
     */
    /** mouse: the program wants mouse events; motion: also plain mouse movement (a program reads /dev/input). */
    public record ScreenState(int containerId, boolean on, boolean graphics, boolean mouse, boolean motion) implements CustomPacketPayload {
        public static final Type<ScreenState> TYPE = Payloads.type("screen_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenState> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ScreenState::containerId, ByteBufCodecs.BOOL, ScreenState::on, ByteBufCodecs.BOOL, ScreenState::graphics,
            ByteBufCodecs.BOOL, ScreenState::mouse, ByteBufCodecs.BOOL, ScreenState::motion, ScreenState::new);

        @Override
        public Type<ScreenState> type() {
            return TYPE;
        }
    }

    /** Server to client: pixel monitor contents, to players tracking the chunk. */
    public record Monitor(BlockPos pos, FbData data) implements CustomPacketPayload {
        public static final Type<Monitor> TYPE = Payloads.type("monitor");
        public static final StreamCodec<RegistryFriendlyByteBuf, Monitor> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, Monitor::pos, FbData.CODEC, Monitor::data, Monitor::new);

        @Override
        public Type<Monitor> type() {
            return TYPE;
        }
    }

    /**
     * Client to server: user input on an open computer. {@code event} is a CC event name; the meaning of a, b, c
     * depends on it (key: code, repeat, modifiers; mouse: button/direction, x, y as framebuffer pixels in graphics
     * mode or 1-based cells in text mode; resize: columns, rows). Pastes are split into chunks of {@link #MAX_TEXT}.
     */
    public record Input(int containerId, String event, int a, int b, int c, String text) implements CustomPacketPayload {
        public static final int MAX_TEXT = 8192;
        public static final Type<Input> TYPE = Payloads.type("input");
        public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Input::containerId, ByteBufCodecs.stringUtf8(32), Input::event,
            ByteBufCodecs.VAR_INT, Input::a, ByteBufCodecs.VAR_INT, Input::b, ByteBufCodecs.VAR_INT, Input::c,
            ByteBufCodecs.stringUtf8(MAX_TEXT), Input::text, Input::new);


        @Override
        public Type<Input> type() {
            return TYPE;
        }
    }

    public static final int POWER_ON = 0;
    public static final int POWER_SHUTDOWN = 1;
    public static final int POWER_REBOOT = 2;
    public static final int POWER_TERMINATE = 3;

    /** Client to server: power buttons / held shortcuts. */
    public record Power(int containerId, int action) implements CustomPacketPayload {
        public static final Type<Power> TYPE = Payloads.type("power");
        public static final StreamCodec<RegistryFriendlyByteBuf, Power> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Power::containerId, ByteBufCodecs.VAR_INT, Power::action, Power::new);

        @Override
        public Type<Power> type() {
            return TYPE;
        }
    }

    /**
     * Client to server: files dropped on a computer window (from the desktop's file manager). Lists what will come:
     * paths relative to the drop (folders end with '/'), with sizes; the contents follow as {@link UploadData}.
     */
    public record UploadStart(int containerId, int uploadId, java.util.List<String> paths, java.util.List<Long> sizes) implements CustomPacketPayload {
        public static final int MAX_FILES = 1000;
        public static final Type<UploadStart> TYPE = Payloads.type("upload_start");
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadStart> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, UploadStart::containerId, ByteBufCodecs.VAR_INT, UploadStart::uploadId,
            ByteBufCodecs.stringUtf8(1024).apply(ByteBufCodecs.list(MAX_FILES)), UploadStart::paths,
            ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list(MAX_FILES)), UploadStart::sizes, UploadStart::new);

        @Override
        public Type<UploadStart> type() {
            return TYPE;
        }
    }

    /** Client to server: the next piece of the dropped files' contents, in order; {@code last} writes them. */
    public record UploadData(int containerId, int uploadId, byte[] data, boolean last, boolean overwrite) implements CustomPacketPayload {
        /** Below the 32 KiB limit of server-bound packets. */
        public static final int CHUNK = 30_000;
        public static final Type<UploadData> TYPE = Payloads.type("upload_data");
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadData> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, UploadData::containerId, ByteBufCodecs.VAR_INT, UploadData::uploadId,
            ByteBufCodecs.byteArray(CHUNK), UploadData::data, ByteBufCodecs.BOOL, UploadData::last,
            ByteBufCodecs.BOOL, UploadData::overwrite, UploadData::new);

        @Override
        public Type<UploadData> type() {
            return TYPE;
        }
    }

    public static final int UPLOAD_READY = 0;
    public static final int UPLOAD_DONE = 1;
    public static final int UPLOAD_FAILED = 2;

    /**
     * Server to client: answer to an upload. READY: send the data ({@code conflicts}: files that already exist in
     * {@code dir}); DONE: written ({@code count} files); FAILED: {@code message} says why.
     */
    public record UploadReply(int uploadId, int status, String dir, java.util.List<String> conflicts, int count, String message)
        implements CustomPacketPayload {
        public static final Type<UploadReply> TYPE = Payloads.type("upload_reply");
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadReply> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, UploadReply::uploadId,
            ByteBufCodecs.VAR_INT, UploadReply::status, ByteBufCodecs.stringUtf8(4096), UploadReply::dir,
            ByteBufCodecs.stringUtf8(1024).apply(ByteBufCodecs.list(UploadStart.MAX_FILES)), UploadReply::conflicts,
            ByteBufCodecs.VAR_INT, UploadReply::count, ByteBufCodecs.stringUtf8(1024), UploadReply::message, UploadReply::new);

        @Override
        public Type<UploadReply> type() {
            return TYPE;
        }
    }
}
