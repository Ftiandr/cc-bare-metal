package dev.ccbaremetal.monitor;

import dev.ccbaremetal.BareMetalConfig;
import dev.ccbaremetal.kernel.gfx.Framebuffer;
import dev.ccbaremetal.network.FbData;
import dev.ccbaremetal.network.Payloads;
import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * One block of a pixel monitor. The origin block (bottom-left as seen from the front) owns the framebuffer and the
 * peripheral for the whole group; other blocks delegate to it.
 */
public class PixelMonitorBlockEntity extends BlockEntity {
    private static final int MAX_PIXELS = 1024;

    private int gx;
    private int gy;
    private int gw = 1;
    private int gh = 1;

    private Framebuffer framebuffer;
    private PixelMonitorPeripheral peripheral;
    private volatile boolean dirty;
    private int cooldown;

    /** Client side image state, owned by the renderer. */
    public Object clientState;

    public PixelMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.PIXEL_MONITOR_BE.get(), pos, state);
    }

    public Direction facing() {
        return getBlockState().getValue(PixelMonitorBlock.FACING);
    }

    public int groupX() {
        return gx;
    }

    public int groupY() {
        return gy;
    }

    public int groupWidth() {
        return gw;
    }

    public int groupHeight() {
        return gh;
    }

    public boolean isOrigin() {
        return gx == 0 && gy == 0;
    }

    public BlockPos originPos() {
        return MonitorGroups.origin(worldPosition, facing(), gx, gy);
    }

    public PixelMonitorBlockEntity origin() {
        if (isOrigin()) return this;
        return level != null && level.getBlockEntity(originPos()) instanceof PixelMonitorBlockEntity be ? be : this;
    }

    /** The peripheral of this block's group (shared by all its blocks). */
    public PixelMonitorPeripheral peripheral() {
        var origin = origin();
        if (origin.peripheral == null) origin.peripheral = new PixelMonitorPeripheral(origin);
        return origin.peripheral;
    }

    public Framebuffer framebuffer() {
        var origin = origin();
        if (origin.framebuffer == null) origin.createFramebuffer();
        return origin.framebuffer;
    }

    private void createFramebuffer() {
        var ppb = BareMetalConfig.get(BareMetalConfig.MONITOR_PIXELS_PER_BLOCK);
        var maxW = Math.min(MAX_PIXELS, gw * 128);
        var maxH = Math.min(MAX_PIXELS, gh * 128);
        framebuffer = new Framebuffer(maxW, maxH, Math.min(maxW, gw * ppb), Math.min(maxH, gh * ppb), 4);
        dirty = true;
    }

    void markDirty() {
        origin().dirty = true;
    }

    void setGroup(int x, int y, int w, int h) {
        if (x == gx && y == gy && w == gw && h == gh) return;
        var wasOrigin = isOrigin();
        gx = x;
        gy = y;
        gw = w;
        gh = h;
        if (isOrigin()) {
            createFramebuffer();
            if (peripheral != null) peripheral.queueEvent("monitor_resize");
        } else if (wasOrigin) {
            framebuffer = null;
        }
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        // Peripherals attached to any block of the group need to see the new shared peripheral.
        invalidateCapabilities();
    }

    /**
     * Width of the frame around the picture, in blocks: drawn by the front texture (connected, so only around the
     * whole wall) and left out by the renderer. Touches are measured inside it.
     */
    public static final double BEZEL = 2.0 / 16.0;

    /** A player right-clicked the front: report the pixel under the cursor (clicks on the frame do nothing). */
    void touch(Vec3 hit) {
        var origin = origin();
        var fb = origin.framebuffer();
        var facing = facing();
        var right = MonitorGroups.right(facing);
        var originPos = origin.getBlockPos();
        // Position on the screen plane in blocks, from the left edge and from the top edge.
        double u = (hit.x - originPos.getX()) * right.getStepX() + (hit.z - originPos.getZ()) * right.getStepZ();
        if (right.getStepX() < 0 || right.getStepZ() < 0) u += 1;
        double v = originPos.getY() + gh - hit.y;
        var pixel = touchedPixel(u, v, gw, gh, fb.width(), fb.height());
        if (pixel != null) origin.peripheral().queueEvent("pixel_monitor_touch", pixel[0], pixel[1]);
    }

    /**
     * The pixel at a point of a wall of {@code gw} x {@code gh} blocks ({@code u}, {@code v} in blocks from its
     * top-left corner), or null on the frame. The picture covers the wall minus the frame on its outer edges -
     * there is no frame between blocks.
     */
    static int[] touchedPixel(double u, double v, int gw, int gh, int width, int height) {
        double su = u - BEZEL, sv = v - BEZEL;
        double screenW = gw - 2 * BEZEL, screenH = gh - 2 * BEZEL;
        if (su < 0 || sv < 0 || su >= screenW || sv >= screenH) return null;
        var px = (int) Math.floor(su / screenW * width);
        var py = (int) Math.floor(sv / screenH * height);
        return new int[]{Math.max(0, Math.min(width - 1, px)), Math.max(0, Math.min(height - 1, py))};
    }

    public void serverTick() {
        if (!isOrigin() || !dirty || !(level instanceof ServerLevel serverLevel)) return;
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        dirty = false;
        var update = framebuffer().takeDirty();
        if (update == null) return;
        var data = FbData.of(update);
        var limit = BareMetalConfig.get(BareMetalConfig.MAX_SYNC_KB_PER_TICK) * 1024;
        cooldown = data.packed().length / Math.max(1, limit);
        PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new net.minecraft.world.level.ChunkPos(worldPosition),
            new Payloads.Monitor(worldPosition, data));
    }

    /** Send the whole picture to one player (they just started watching this chunk). */
    public void sendFull(ServerPlayer player) {
        if (!isOrigin()) return;
        PacketDistributor.sendToPlayer(player, new Payloads.Monitor(worldPosition, FbData.of(framebuffer().full())));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("GroupX", gx);
        tag.putInt("GroupY", gy);
        tag.putInt("GroupW", gw);
        tag.putInt("GroupH", gh);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        gx = tag.getInt("GroupX");
        gy = tag.getInt("GroupY");
        gw = Math.max(1, tag.getInt("GroupW"));
        gh = Math.max(1, tag.getInt("GroupH"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Render bounds cover the whole group so the screen isn't culled when only the origin is off-screen. */
    public AABB renderBounds() {
        var right = MonitorGroups.right(facing());
        var far = worldPosition.relative(right, gw - 1).above(gh - 1);
        return new AABB(Vec3.atLowerCornerOf(worldPosition), Vec3.atLowerCornerOf(far)).inflate(1);
    }
}
