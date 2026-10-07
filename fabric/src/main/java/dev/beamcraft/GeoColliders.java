package dev.beamcraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

/**
 * BeamNG's real collision geometry around Steve, sampled by BeamNG on a fine grid (exact floor
 * heights, walls, pillars, undersides of bridges) and used by Minecraft's movement physics instead
 * of the 1 m barrier blocks.
 *
 * <p>{@code geo <n> {id x0 y0 z0 x1 y1 z1}...} adds/replaces boxes (Minecraft coordinates, id names
 * the grid cell; an empty box removes the cell), {@code geoclear} forgets everything.
 */
public final class GeoColliders {
    private static final double KEEP_RADIUS = 12; // boxes further than this from Steve are dropped

    private record Cell(Box box, VoxelShape shape) {}

    private static final Map<String, Cell> cells = new ConcurrentHashMap<>();
    private static volatile boolean active;

    private GeoColliders() {}

    /** Steve walks on BeamNG's geometry (instead of barrier blocks) once BeamNG has sent some. */
    public static boolean active() {
        return active;
    }

    public static void update(String[] a) {
        int n = Integer.parseInt(a[1]);
        for (int c = 0; c < n; c++) {
            int i = 2 + c * 7;
            String id = a[i];
            double x0 = Double.parseDouble(a[i + 1]), y0 = Double.parseDouble(a[i + 2]), z0 = Double.parseDouble(a[i + 3]);
            double x1 = Double.parseDouble(a[i + 4]), y1 = Double.parseDouble(a[i + 5]), z1 = Double.parseDouble(a[i + 6]);
            if (x1 <= x0 || y1 <= y0 || z1 <= z0) {
                cells.remove(id);
            } else {
                Box box = new Box(x0, y0, z0, x1, y1, z1);
                cells.put(id, new Cell(box, VoxelShapes.cuboid(box)));
            }
        }
        active = true;
    }

    public static void clear() {
        cells.clear();
        active = false;
    }

    /** Shapes touching the area Steve is about to move through. */
    public static void collect(Box area, List<VoxelShape> out) {
        for (Cell c : cells.values()) {
            if (c.box.intersects(area)) out.add(c.shape);
        }
    }

    /**
     * Safety net: if Steve ends up slightly inside a floor (e.g. right after a teleport), lift him
     * onto it. Minecraft never pushes an entity out of something it's already inside, so without
     * this he would fall straight through. Returns the height to put his feet at, or NaN.
     */
    public static double floorToClimbOnto(Box player, double velocityY) {
        if (velocityY > 0.01) return Double.NaN; // jumping up: leave him alone
        double best = Double.NaN;
        Box feet = new Box(player.minX + 0.05, player.minY, player.minZ + 0.05, player.maxX - 0.05, player.minY + 0.6, player.maxZ - 0.05);
        for (Cell c : cells.values()) {
            Box b = c.box;
            if (!b.intersects(feet)) continue;
            double inside = b.maxY - player.minY; // how deep his feet are in this box
            if (inside > 1e-4 && inside <= 0.6 && b.maxY - b.minY >= 0.5 && (Double.isNaN(best) || b.maxY > best)) best = b.maxY;
        }
        return best;
    }

    /** Forget cells Steve has left far behind (BeamNG re-sends them if he comes back). */
    public static void prune(double x, double y, double z) {
        List<String> far = new ArrayList<>();
        for (Map.Entry<String, Cell> e : cells.entrySet()) {
            Box b = e.getValue().box;
            double cx = (b.minX + b.maxX) / 2, cz = (b.minZ + b.maxZ) / 2;
            if (Math.abs(cx - x) > KEEP_RADIUS || Math.abs(cz - z) > KEEP_RADIUS) far.add(e.getKey());
        }
        for (String id : far) cells.remove(id);
    }
}
