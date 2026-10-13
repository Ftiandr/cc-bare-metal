package dev.ccbaremetal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dan200.computercraft.api.turtle.TurtleAnimation;
import dan200.computercraft.api.turtle.TurtleSide;
import dev.ccbaremetal.turtle.TurtleUpgrades;
import dev.ccbaremetal.turtle.WasmTurtle;
import dev.ccbaremetal.turtle.WasmTurtleBlock;
import dev.ccbaremetal.turtle.WasmTurtleBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/**
 * Draws a WASM turtle: its body (the block model), its upgrades with the models CC: Tweaked uses for them (so addon
 * upgrades look as they do on CC's turtles), and the movement and turning animations.
 */
public class WasmTurtleRenderer implements BlockEntityRenderer<WasmTurtleBlockEntity> {
    private static final TurtleAnimation[] ANIMATIONS = TurtleAnimation.values();

    public WasmTurtleRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** Model rotation of a facing (as blockstate "y" rotations: north 0, east 90...). */
    private static float yaw(Direction facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
    }

    @Override
    public void render(WasmTurtleBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var level = be.getLevel();
        if (level == null) return;
        var facing = be.getBlockState().getValue(WasmTurtleBlock.FACING);
        var animation = be.animation >= 0 && be.animation < ANIMATIONS.length ? ANIMATIONS[be.animation] : TurtleAnimation.NONE;
        var progress = Math.min(1f, Math.max(0f, (level.getGameTime() - be.animationStart + partialTick) / WasmTurtle.MOVE_TICKS));
        var yaw = yaw(facing);

        pose.pushPose();
        // Moving: drawn on the way from the block it left.
        Direction moved = switch (animation) {
            case MOVE_FORWARD -> facing;
            case MOVE_BACK -> facing.getOpposite();
            case MOVE_UP -> Direction.UP;
            case MOVE_DOWN -> Direction.DOWN;
            default -> null;
        };
        if (moved != null && progress < 1) {
            var back = 1 - progress;
            pose.translate(-moved.getStepX() * back, -moved.getStepY() * back, -moved.getStepZ() * back);
        }
        // Turning: from the direction it faced before.
        if (animation == TurtleAnimation.TURN_LEFT && progress < 1) yaw += 90 * (1 - progress);
        if (animation == TurtleAnimation.TURN_RIGHT && progress < 1) yaw -= 90 * (1 - progress);
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        pose.translate(-0.5, -0.5, -0.5);

        var minecraft = Minecraft.getInstance();
        var buffer = buffers.getBuffer(Sheets.translucentCullBlockSheet());
        var body = minecraft.getBlockRenderer().getBlockModel(be.getBlockState());
        render(pose, buffer, body, light, overlay);
        renderUpgrade(be.clientLeft, TurtleSide.LEFT, pose, buffers, light, overlay);
        renderUpgrade(be.clientRight, TurtleSide.RIGHT, pose, buffers, light, overlay);
        pose.popPose();
    }

    private static void render(PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer buffer, BakedModel model, int light, int overlay) {
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffer, null, model, 1, 1, 1, light, overlay);
    }

    /** An upgrade, with the model CC: Tweaked (or the addon) registered for it. */
    private static void renderUpgrade(ItemStack item, TurtleSide side, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (item.isEmpty()) return;
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        var holder = TurtleUpgrades.find(level.registryAccess(), item);
        if (holder == null) return;
        try {
            var upgrade = holder.value();
            var model = dan200.computercraft.client.turtle.TurtleUpgradeModellers.getModel(upgrade, upgrade.getUpgradeData(item), side);
            pose.pushPose();
            pose.mulPose(model.matrix().getMatrix());
            render(pose, buffers.getBuffer(Sheets.translucentCullBlockSheet()), model.model(), light, overlay);
            pose.popPose();
        } catch (RuntimeException e) {
            // A model CC cannot give (an upgrade without a modeller): draw the turtle without it.
        }
    }
}
