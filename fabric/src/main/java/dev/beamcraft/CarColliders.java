package dev.beamcraft;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

/**
 * BeamNG cars near Steve, as solid collision boxes for Minecraft's player physics, so Steve bumps
 * into cars (and can stand on them) instead of walking through.
 *
 * <p>{@code cars <n> {cx cy cz  ax ay az  bx by bz  hh}...} in Minecraft coordinates: the car's
 * centre, its half-length axis, its half-width axis (both horizontal) and its half height. Minecraft
 * only knows axis-aligned boxes, so a turned car is approximated by thin slices along its length.
 */
public final class CarColliders {
    private static volatile List<VoxelShape> shapes = List.of();

    private CarColliders() {}

    public static List<VoxelShape> shapes() {
        return shapes;
    }

    public static void update(String[] a) {
        int n = Integer.parseInt(a[1]);
        List<VoxelShape> out = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            int i = 2 + c * 10;
            double cx = d(a[i]), cy = d(a[i + 1]), cz = d(a[i + 2]);
            double ax = d(a[i + 3]), az = d(a[i + 5]);
            double bx = d(a[i + 6]), bz = d(a[i + 8]);
            double hh = d(a[i + 9]);
            double length = 2 * Math.hypot(ax, az);
            int slices = Math.max(1, (int) Math.ceil(length / 0.4));
            for (int s = 0; s < slices; s++) {
                double t0 = -1 + 2.0 * s / slices, t1 = -1 + 2.0 * (s + 1) / slices;
                double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
                for (double t : new double[] {t0, t1}) {
                    for (int side = -1; side <= 1; side += 2) {
                        double x = cx + ax * t + bx * side, z = cz + az * t + bz * side;
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minZ = Math.min(minZ, z);
                        maxZ = Math.max(maxZ, z);
                    }
                }
                out.add(VoxelShapes.cuboid(new Box(minX, cy - hh, minZ, maxX, cy + hh, maxZ)));
            }
        }
        shapes = out;
    }

    public static void clear() {
        shapes = List.of();
    }

    private static double d(String s) {
        return Double.parseDouble(s);
    }
}
