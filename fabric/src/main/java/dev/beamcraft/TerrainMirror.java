package dev.beamcraft;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Copies BeamNG's ground (and water) around Steve into Minecraft: invisible barrier blocks to place
 * blocks on and for dropped items to land on, and water source blocks wherever BeamNG has a lake or
 * sea, so boats float and Steve swims. Runs on the server thread only.
 */
public final class TerrainMirror {
    /** How many barrier blocks to place under the surface. */
    private static final int DEPTH = 4;
    /**
     * How the invisible ground is written: clients are told, but neighbours aren't updated, so redstone,
     * rails, torches... resting on it don't pop off while a column is rebuilt.
     */
    private static final int QUIET = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;
    /** Set while we write blocks, so BlockSync ignores our own changes. */
    public static volatile boolean writing;

    /** column key -> {top, bottom, waterTop}; barriers fill [bottom, top - 1], water [top, waterTop - 1]. */
    private static final Map<Long, int[]> columns = new HashMap<>();
    /** water we placed (never flows: see FlowableFluidMixin) */
    private static final Set<Long> water = ConcurrentHashMap.newKeySet();

    private TerrainMirror() {}

    public static void reset() {
        columns.clear();
        water.clear();
    }

    public static boolean isMirroredWater(BlockPos pos) {
        return water.contains(pos.asLong());
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /**
     * @param top      first air block above the BeamNG ground in this column
     * @param feetY    the player's current block Y; columns higher than him reach down to it
     * @param waterTop first block above BeamNG's water surface here, or Integer.MIN_VALUE for no water
     */
    public static void apply(ServerWorld world, int x, int z, int top, int feetY, int waterTop) {
        int minY = world.getBottomY();
        int maxY = world.getTopY() - 1;
        top = Math.max(minY + 1, Math.min(top, maxY));
        int bottom = top - DEPTH;
        if (top > feetY + 1) bottom = Math.min(bottom, feetY - 2);
        bottom = Math.max(bottom, minY);
        waterTop = waterTop <= top ? Integer.MIN_VALUE : Math.min(waterTop, maxY);

        long k = key(x, z);
        int[] old = columns.get(k);
        if (old != null && old[0] == top && old[1] <= bottom && old[2] == waterTop) return;

        BlockPos.Mutable pos = new BlockPos.Mutable();
        writing = true;
        try {
            if (old != null && old[0] == top && old[2] == waterTop) {
                // same surface, the column just needs to reach further down
                fill(world, pos, x, z, bottom, old[1] - 1);
                old[1] = bottom;
                return;
            }
            if (old != null) {
                if (old[2] != Integer.MIN_VALUE) clearWater(world, pos, x, z, old[0], old[2] - 1);
                clear(world, pos, x, z, old[1], old[0] - 1);
            } else {
                // first time this session: remove stale barriers above the ground (e.g. from an
                // earlier session where a roof was mistaken for ground)
                clear(world, pos, x, z, top, Math.min(maxY, top + 64));
            }
            fill(world, pos, x, z, bottom, top - 1);
            if (waterTop != Integer.MIN_VALUE) fillWater(world, pos, x, z, top, waterTop - 1);
            columns.put(k, new int[] {top, bottom, waterTop});
        } finally {
            writing = false;
        }
    }

    private static void fill(ServerWorld world, BlockPos.Mutable pos, int x, int z, int from, int to) {
        from = Math.max(from, world.getBottomY());
        to = Math.min(to, world.getTopY() - 1);
        BlockState barrier = Blocks.BARRIER.getDefaultState();
        for (int y = from; y <= to; y++) {
            pos.set(x, y, z);
            if (world.getBlockState(pos).isAir()) world.setBlockState(pos, barrier, QUIET);
        }
    }

    private static void clear(ServerWorld world, BlockPos.Mutable pos, int x, int z, int from, int to) {
        from = Math.max(from, world.getBottomY());
        to = Math.min(to, world.getTopY() - 1);
        BlockState air = Blocks.AIR.getDefaultState();
        for (int y = from; y <= to; y++) {
            pos.set(x, y, z);
            if (world.getBlockState(pos).isOf(Blocks.BARRIER)) world.setBlockState(pos, air, QUIET);
        }
    }

    private static void fillWater(ServerWorld world, BlockPos.Mutable pos, int x, int z, int from, int to) {
        from = Math.max(from, world.getBottomY());
        to = Math.min(to, world.getTopY() - 1);
        BlockState source = Blocks.WATER.getDefaultState();
        for (int y = from; y <= to; y++) {
            pos.set(x, y, z);
            BlockState s = world.getBlockState(pos);
            if (s.isAir() || s.isOf(Blocks.WATER)) {
                water.add(pos.asLong()); // before placing, so it never gets a chance to flow
                world.setBlockState(pos, source, QUIET);
            }
        }
    }

    private static void clearWater(ServerWorld world, BlockPos.Mutable pos, int x, int z, int from, int to) {
        from = Math.max(from, world.getBottomY());
        to = Math.min(to, world.getTopY() - 1);
        BlockState air = Blocks.AIR.getDefaultState();
        for (int y = from; y <= to; y++) {
            pos.set(x, y, z);
            if (water.remove(pos.asLong()) && world.getBlockState(pos).isOf(Blocks.WATER)) {
                world.setBlockState(pos, air, QUIET);
            }
        }
    }
}
