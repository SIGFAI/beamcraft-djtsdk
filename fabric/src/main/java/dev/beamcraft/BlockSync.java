package dev.beamcraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Keeps BeamNG's copy of the blocks in step with Minecraft.
 *
 * <ul>
 *   <li>Every block change in the overworld is forwarded as
 *       {@code blk x y z color minX minY minZ maxX maxY maxZ [shape textures...]} or {@code blk x y z -1}.</li>
 *   <li>On top of that the real world around the player is re-scanned, one chunk per tick, and sent
 *       as {@code chunk cx cz yMin yMax}, the chunk's blocks, {@code chunkend cx cz yMin yMax}; BeamNG
 *       then removes anything in that area it shouldn't have. So nothing stays invisible (or
 *       ghost-solid) even if a packet was lost or the block list on disk was out of date.</li>
 * </ul>
 */
public final class BlockSync {
    private static final String FILE = "beamcraft_blocks.txt";
    /** Lines per client tick, so a big resend doesn't overflow BeamNG's socket buffer. */
    private static final int LINES_PER_TICK = 150;
    /** Chunks around the player that are re-checked continuously (radius 4 = 9x9 chunks, ~4 s per sweep). */
    private static final int SCAN_RADIUS = 4;
    private static final int SCAN_HEIGHT = 64;

    /** {@code solid}: has collision; otherwise only shown (levers, redstone dust, torches, flowers...) */
    private record Entry(int x, int y, int z, BlockState state, int color,
                         double minX, double minY, double minZ, double maxX, double maxY, double maxZ, boolean solid) {}

    private record ChunkReport(int cx, int cz, int yMin, int yMax, List<Long> blocks) {}

    private static final Map<Long, Entry> known = new ConcurrentHashMap<>();
    /** positions whose block changed (sent on the client thread, where textures can be looked up) */
    private static final ConcurrentLinkedQueue<Long> changed = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> outbox = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<ChunkReport> reports = new ConcurrentLinkedQueue<>();
    private static int scanStep;

    private BlockSync() {}

    /** A block as BeamNG sees it, or null if BeamNG shouldn't show anything there. */
    private static Entry entryFor(World world, BlockPos pos, BlockState state) {
        if (state.isAir() || state.isOf(Blocks.BARRIER)) return null;
        VoxelShape shape = state.getCollisionShape(world, pos);
        boolean solid = !shape.isEmpty();
        if (!solid) {
            // nothing to bump into, but still something to see (lever, redstone, torch, rail, flower...)
            if (state.getRenderType() != BlockRenderType.MODEL) return null; // e.g. water: BeamNG has its own
            shape = state.getOutlineShape(world, pos);
            if (shape.isEmpty()) return null;
        }
        Box b = shape.getBoundingBox();
        return new Entry(pos.getX(), pos.getY(), pos.getZ(), state, state.getMapColor(world, pos).id,
                b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, solid);
    }

    /** Server thread. */
    public static void onBlockChanged(World world, BlockPos pos, BlockState state) {
        if (TerrainMirror.writing) return;
        long key = pos.asLong();
        Entry entry = entryFor(world, pos, state);
        if (entry == null) {
            if (known.remove(key) == null) return;
        } else {
            Entry old = known.put(key, entry);
            if (entry.equals(old)) return;
        }
        changed.add(key);
    }

    /**
     * Server thread, every tick while BeamNG is connected: re-read one chunk near the player from the
     * actual world, fix up the block list from it, and queue the chunk for BeamNG.
     */
    public static void scanNext(ServerWorld world, BlockPos player) {
        int side = SCAN_RADIUS * 2 + 1;
        int i = scanStep++ % (side * side);
        int cx = (player.getX() >> 4) + (i % side) - SCAN_RADIUS;
        int cz = (player.getZ() >> 4) + (i / side) - SCAN_RADIUS;
        WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
        if (chunk == null) return; // not loaded: nothing can have changed there
        int yMin = Math.max(world.getBottomY(), player.getY() - SCAN_HEIGHT);
        int yMax = Math.min(world.getTopY() - 1, player.getY() + SCAN_HEIGHT);
        List<Long> found = new ArrayList<>();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int sy = yMin >> 4; sy <= yMax >> 4; sy++) {
            int index = world.getSectionIndex(sy << 4);
            if (index < 0 || index >= chunk.getSectionArray().length) continue;
            ChunkSection section = chunk.getSection(index);
            boolean empty = section.isEmpty();
            for (int ly = 0; ly < 16; ly++) {
                int y = (sy << 4) + ly;
                if (y < yMin || y > yMax) continue;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        pos.set((cx << 4) + lx, y, (cz << 4) + lz);
                        long key = pos.asLong();
                        Entry entry = empty ? null : entryFor(world, pos, section.getBlockState(lx, ly, lz));
                        if (entry != null) {
                            known.put(key, entry);
                            found.add(key);
                        } else {
                            known.remove(key);
                        }
                    }
                }
            }
        }
        reports.add(new ChunkReport(cx, cz, yMin, yMax, found));
    }

    /** Called when BeamNG (re)connects: it starts from an empty scene, so send everything. */
    public static void resendAll() {
        outbox.add("blkclear");
        changed.addAll(known.keySet());
    }

    /** Client thread: turn queued changes into lines (exporting textures as needed) and send some. */
    public static void drainTo(MinecraftClient mc) {
        int budget = LINES_PER_TICK;
        String line;
        while (budget > 0 && (line = outbox.poll()) != null) {
            Bridge.send(line);
            budget--;
        }
        Long key;
        while (budget > 0 && (key = changed.poll()) != null) {
            Bridge.send(lineFor(mc, key));
            budget--;
        }
        ChunkReport r;
        while (budget > 0 && (r = reports.poll()) != null) {
            String range = r.cx + " " + r.cz + " " + r.yMin + " " + r.yMax;
            Bridge.send("chunk " + range);
            for (long k : r.blocks) Bridge.send(lineFor(mc, k));
            Bridge.send("chunkend " + range);
            budget -= r.blocks.size() + 2;
        }
    }

    private static String lineFor(MinecraftClient mc, long key) {
        Entry e = known.get(key);
        if (e == null) {
            BlockPos p = BlockPos.fromLong(key);
            return String.format(Locale.ROOT, "blk %d %d %d -1", p.getX(), p.getY(), p.getZ());
        }
        // the block's real model (stairs, fences...) fills its cell; it's placed unscaled
        // "blkd": a block with no collision, shown only
        String cmd = e.solid ? "blk" : "blkd";
        String[] model = TextureExport.modelFor(mc, e.state);
        if (model != null) {
            return String.format(Locale.ROOT, "%s %d %d %d %d 0 0 0 1 1 1 ", cmd, e.x, e.y, e.z, e.color) + String.join(" ", model);
        }
        String line = String.format(Locale.ROOT, "%s %d %d %d %d %.4f %.4f %.4f %.4f %.4f %.4f",
                cmd, e.x, e.y, e.z, e.color, e.minX, e.minY, e.minZ, e.maxX, e.maxY, e.maxZ);
        String[] tex = TextureExport.shapeFor(mc, e.state);
        if (tex != null) line += " " + String.join(" ", tex);
        return line;
    }

    private static Path file(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve(FILE);
    }

    public static void load(MinecraftServer server) {
        known.clear();
        changed.clear();
        outbox.clear();
        reports.clear();
        Path f = file(server);
        if (!Files.exists(f)) return;
        int skipped = 0;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                try {
                    String[] a = line.split(" ");
                    BlockState state = Block.getStateFromRawId(Integer.parseInt(a[3]));
                    Entry e = new Entry(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), state,
                            Integer.parseInt(a[4]), Double.parseDouble(a[5]), Double.parseDouble(a[6]), Double.parseDouble(a[7]),
                            Double.parseDouble(a[8]), Double.parseDouble(a[9]), Double.parseDouble(a[10]),
                            a.length < 12 || !a[11].equals("0"));
                    known.put(BlockPos.asLong(e.x, e.y, e.z), e);
                } catch (RuntimeException bad) {
                    skipped++; // old or damaged line; the world scan will find that block again anyway
                }
            }
            BeamCraftClient.LOG.info("BeamCraft loaded {} blocks ({} unreadable lines skipped)", known.size(), skipped);
        } catch (IOException e) {
            BeamCraftClient.LOG.warn("BeamCraft could not read {}: {}", f, e.toString());
        }
    }

    public static void save(MinecraftServer server) {
        Path f = file(server);
        List<String> lines = new ArrayList<>();
        for (Entry e : known.values()) {
            lines.add(String.format(Locale.ROOT, "%d %d %d %d %d %.4f %.4f %.4f %.4f %.4f %.4f %d", e.x, e.y, e.z,
                    Block.getRawIdFromState(e.state), e.color, e.minX, e.minY, e.minZ, e.maxX, e.maxY, e.maxZ, e.solid ? 1 : 0));
        }
        try {
            Files.write(f, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            BeamCraftClient.LOG.warn("BeamCraft could not save {}: {}", f, e.toString());
        }
    }
}
