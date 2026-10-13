package dev.ccbaremetal.monitor;

import dev.ccbaremetal.BareMetalConfig;
import dev.ccbaremetal.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Multiblock formation: the connected monitors (same facing, same plane) form one screen when they make a full
 * rectangle within the size limit; otherwise every block is its own screen.
 */
public final class MonitorGroups {
    private MonitorGroups() {
    }

    /** The viewer's "right" direction for a screen facing {@code facing}. */
    public static Direction right(Direction facing) {
        return facing.getCounterClockWise();
    }

    public static void rebuild(Level level, BlockPos start, Direction facing) {
        var right = right(facing);
        Set<BlockPos> component = new HashSet<>();
        var queue = new ArrayDeque<BlockPos>();
        queue.add(start);
        component.add(start);
        while (!queue.isEmpty() && component.size() <= 256) {
            var pos = queue.poll();
            for (var dir : new Direction[]{right, right.getOpposite(), Direction.UP, Direction.DOWN}) {
                var next = pos.relative(dir);
                if (component.contains(next)) continue;
                var state = level.getBlockState(next);
                if (!state.is(ModRegistry.PIXEL_MONITOR.get()) || state.getValue(PixelMonitorBlock.FACING) != facing) continue;
                component.add(next);
                queue.add(next);
            }
        }

        int minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (var pos : component) {
            var u = coord(pos, right);
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
        }
        var width = maxU - minU + 1;
        var height = maxY - minY + 1;
        var rectangle = component.size() == width * height
            && width <= BareMetalConfig.get(BareMetalConfig.MONITOR_MAX_WIDTH) && height <= BareMetalConfig.get(BareMetalConfig.MONITOR_MAX_HEIGHT);

        for (var pos : component) {
            if (!(level.getBlockEntity(pos) instanceof PixelMonitorBlockEntity be)) continue;
            int x = 0, y = 0, w = 1, h = 1;
            if (rectangle) {
                x = coord(pos, right) - minU;
                y = pos.getY() - minY;
                w = width;
                h = height;
            }
            be.setGroup(x, y, w, h);
            // Connected textures: the frame is drawn only around the whole screen.
            var state = level.getBlockState(pos);
            var connected = state.setValue(PixelMonitorBlock.LEFT, x > 0).setValue(PixelMonitorBlock.RIGHT, x < w - 1)
                .setValue(PixelMonitorBlock.DOWN, y > 0).setValue(PixelMonitorBlock.UP, y < h - 1);
            if (connected != state) level.setBlock(pos, connected, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    /** Position along the "right" axis, increasing to the viewer's right. */
    static int coord(BlockPos pos, Direction right) {
        return pos.getX() * right.getStepX() + pos.getZ() * right.getStepZ();
    }

    /** Origin (bottom-left as seen by the viewer) of a block's group. */
    static BlockPos origin(BlockPos pos, Direction facing, int x, int y) {
        return pos.relative(right(facing), -x).below(y);
    }
}
