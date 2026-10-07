package dev.beamcraft;

import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;

/** Everything that has to touch the integrated server (runs work on the server thread). */
public final class ServerSide {
    private static volatile MinecraftServer server;

    private ServerSide() {}

    public static void onServerStarted(MinecraftServer s) {
        server = s;
        TerrainMirror.reset();
        BlockSync.load(s);
        GameRules rules = s.getGameRules();
        // Mobs would be invisible in BeamNG, and items dropped on death fall into the void.
        rules.get(GameRules.DO_MOB_SPAWNING).set(false, s);
        rules.get(GameRules.DO_PATROL_SPAWNING).set(false, s);
        rules.get(GameRules.DO_TRADER_SPAWNING).set(false, s);
        rules.get(GameRules.DO_WARDEN_SPAWNING).set(false, s);
        rules.get(GameRules.DO_INSOMNIA).set(false, s);
        rules.get(GameRules.DO_IMMEDIATE_RESPAWN).set(true, s);
        rules.get(GameRules.KEEP_INVENTORY).set(true, s);
        rules.get(GameRules.DO_FIRE_TICK).set(false, s);
        // BeamNG decides the time of day (so Steve and the hand are lit like BeamNG's world)
        rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, s);
        rules.get(GameRules.DO_WEATHER_CYCLE).set(false, s);
    }

    /**
     * {@code carhit <speed> <vx> <vy> <vz>}: a BeamNG car ran into Steve at {@code speed} m/s.
     * Damage grows with speed (like falling), and Steve is thrown along the car's direction.
     */
    public static void carHit(MinecraftClient mc, float speed, double vx, double vy, double vz) {
        MinecraftServer s = server;
        UUID id = playerId(mc);
        if (s == null || id == null) return;
        s.execute(() -> {
            ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
            if (p == null || p.isCreative() || p.isSpectator()) {
                if (p != null) knock(p, vx, vy, vz);
                return;
            }
            p.damage(p.getDamageSources().flyIntoWall(), Math.max(0, (speed - 3f) * 1.5f));
            knock(p, vx, vy, vz);
        });
    }

    private static void knock(ServerPlayerEntity p, double vx, double vy, double vz) {
        // m/s -> blocks per tick, with a little lift so Steve is thrown rather than slid
        p.setVelocity(vx / 20.0 * 0.8, Math.max(vy / 20.0, 0.35), vz / 20.0 * 0.8);
        p.velocityModified = true;
    }

    /** {@code time <ticks>}: Minecraft time of day matching BeamNG's sun. */
    public static void setTime(long ticks) {
        MinecraftServer s = server;
        if (s == null) return;
        s.execute(() -> s.getOverworld().setTimeOfDay(ticks));
    }

    /** Every server tick while BeamNG is connected: re-check one chunk around the player. */
    public static void onServerTick(MinecraftServer s) {
        if (!Bridge.connected() || s.getPlayerManager().getPlayerList().isEmpty()) return;
        ServerPlayerEntity p = s.getPlayerManager().getPlayerList().get(0);
        if (p.getServerWorld() != s.getOverworld()) return;
        BlockSync.scanNext(s.getOverworld(), p.getBlockPos());
    }

    public static void onServerStopping(MinecraftServer s) {
        BlockSync.save(s);
        TerrainMirror.reset();
        server = null;
    }

    private static UUID playerId(MinecraftClient mc) {
        return mc.player == null ? null : mc.player.getUuid();
    }

    /** @param force teleport even if the player is already close (used when leaving a car) */
    public static void teleport(MinecraftClient mc, double x, double y, double z, float yaw, boolean force) {
        MinecraftServer s = server;
        UUID id = playerId(mc);
        if (s == null || id == null) return;
        s.execute(() -> {
            ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
            if (p == null) return;
            if (!force && p.squaredDistanceTo(x, y, z) < 4) return;
            double safeY = aboveBlocks(p.getServerWorld(), p, x, y, z);
            p.teleport(p.getServerWorld(), x, safeY, z, yaw, p.getPitch());
            p.fallDistance = 0;
            p.setVelocity(Vec3d.ZERO);
        });
    }

    /**
     * BeamNG may not know about blocks placed since its collision was last rebuilt, so its landing
     * spot can be inside the player's own blocks: move up until Steve's body is clear of them
     * (the invisible barrier ground doesn't count - Steve walks on BeamNG's real ground).
     */
    private static double aboveBlocks(ServerWorld world, ServerPlayerEntity p, double x, double y, double z) {
        double w = p.getWidth() / 2, h = p.getHeight();
        for (int step = 0; step < 16; step++) {
            Box box = new Box(x - w, y, z - w, x + w, y + h, z + w);
            double top = Double.NaN;
            for (BlockPos pos : BlockPos.iterate(BlockPos.ofFloored(box.minX, box.minY, box.minZ),
                    BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
                BlockState s = world.getBlockState(pos);
                if (s.isAir() || s.isOf(Blocks.BARRIER)) continue;
                VoxelShape shape = s.getCollisionShape(world, pos, ShapeContext.of(p));
                if (shape.isEmpty()) continue;
                for (Box b : shape.offset(pos.getX(), pos.getY(), pos.getZ()).getBoundingBoxes()) {
                    if (b.intersects(box) && (Double.isNaN(top) || b.maxY > top)) top = b.maxY;
                }
            }
            if (Double.isNaN(top)) return y;
            y = top + 0.01;
        }
        return y;
    }

    /** Runs a chat command as the player, e.g. {@code /give @s diamond_sword}. */
    public static void command(MinecraftClient mc, String command) {
        MinecraftServer s = server;
        UUID id = playerId(mc);
        if (s == null || id == null) return;
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        s.execute(() -> {
            ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
            if (p != null) s.getCommandManager().executeWithPrefix(p.getCommandSource(), cmd);
        });
    }

    /**
     * {@code terrain x z top [x z top ...]} or, with water, {@code terrainw x z top waterTop ...}
     * (waterTop = first block above BeamNG's water surface, or a very low number for no water).
     */
    public static void terrain(MinecraftClient mc, String[] a) {
        MinecraftServer s = server;
        if (s == null) return;
        int stride = a[0].equals("terrainw") ? 4 : 3;
        int n = (a.length - 1) / stride;
        int[] xs = new int[n], zs = new int[n], tops = new int[n], waters = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = Integer.parseInt(a[1 + i * stride]);
            zs[i] = Integer.parseInt(a[2 + i * stride]);
            tops[i] = Integer.parseInt(a[3 + i * stride]);
            waters[i] = stride == 4 ? Integer.parseInt(a[4 + i * stride]) : Integer.MIN_VALUE;
        }
        UUID id = playerId(mc);
        s.execute(() -> {
            ServerWorld world = s.getOverworld();
            ServerPlayerEntity p = id == null ? null : s.getPlayerManager().getPlayer(id);
            for (int i = 0; i < n; i++) {
                int feet = p != null ? p.getBlockY() : tops[i];
                TerrainMirror.apply(world, xs[i], zs[i], tops[i], feet, waters[i]);
            }
        });
    }
}
