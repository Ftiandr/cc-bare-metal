package dev.ccbaremetal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ccbaremetal.monitor.PixelMonitorBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/** Draws the picture of a pixel monitor group on its front face, full bright. */
public class PixelMonitorRenderer implements BlockEntityRenderer<PixelMonitorBlockEntity> {
    private static final float BEZEL = (float) dev.ccbaremetal.monitor.PixelMonitorBlockEntity.BEZEL; // the frame drawn in the front texture
    private static final float OFFSET = 0.002f;

    public PixelMonitorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(PixelMonitorBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (!be.isOrigin() || !(be.clientState instanceof ScreenImage image)) return;
        image.updateMonitor();
        var facing = be.facing();
        var right = facing.getCounterClockWise();
        var w = be.groupWidth();
        var h = be.groupHeight();

        // Corner of the screen plane at the viewer's bottom-left, in block-local coordinates.
        float baseX = right.getStepX() < 0 ? 1 : 0;
        float baseZ = right.getStepZ() < 0 ? 1 : 0;
        float planeX = facing == Direction.EAST ? 1 + OFFSET : facing == Direction.WEST ? -OFFSET : 0;
        float planeZ = facing == Direction.SOUTH ? 1 + OFFSET : facing == Direction.NORTH ? -OFFSET : 0;
        if (facing.getAxis() == Direction.Axis.X) baseX = planeX;
        else baseZ = planeZ;

        float rx = right.getStepX();
        float rz = right.getStepZ();
        float u0 = BEZEL;
        float u1 = w - BEZEL;
        float v0 = BEZEL;
        float v1 = h - BEZEL;

        var consumer = buffers.getBuffer(RenderType.text(image.texture()));
        var last = pose.last();
        // Bottom-left, bottom-right, top-right, top-left (counter-clockwise seen from the front).
        vertex(consumer, last, baseX + rx * u0, v0, baseZ + rz * u0, 0, 1);
        vertex(consumer, last, baseX + rx * u1, v0, baseZ + rz * u1, 1, 1);
        vertex(consumer, last, baseX + rx * u1, v1, baseZ + rz * u1, 1, 0);
        vertex(consumer, last, baseX + rx * u0, v1, baseZ + rz * u0, 0, 0);
        // Same quad wound the other way, in case culling is enabled for this render type.
        vertex(consumer, last, baseX + rx * u0, v1, baseZ + rz * u0, 0, 0);
        vertex(consumer, last, baseX + rx * u1, v1, baseZ + rz * u1, 1, 0);
        vertex(consumer, last, baseX + rx * u1, v0, baseZ + rz * u1, 1, 1);
        vertex(consumer, last, baseX + rx * u0, v0, baseZ + rz * u0, 0, 1);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v) {
        consumer.addVertex(pose, x, y, z).setColor(255, 255, 255, 255).setUv(u, v).setLight(0xF000F0);
    }

    @Override
    public boolean shouldRenderOffScreen(PixelMonitorBlockEntity be) {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(PixelMonitorBlockEntity be) {
        return be.renderBounds();
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
