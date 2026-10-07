package dev.beamcraft;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

/**
 * Gives BeamNG real Minecraft block textures, taken from the player's own game: for each block the
 * top, side and bottom textures are exported into BeamNG's user folder as PNG + material, plus a
 * cube shape (.dae) that uses them. Runs on the client thread.
 *
 * <pre>
 *   art/beamcraft/tex/&lt;texture&gt;.png             (upscaled so it stays pixel-sharp)
 *   art/beamcraft/tex/&lt;texture&gt;.materials.json
 *   art/beamcraft/gen/&lt;shape&gt;.dae
 * </pre>
 */
public final class TextureExport {
    private static final int SIZE = 128; // 16x16 textures are scaled up 8x with nearest-neighbour

    private static volatile Path root;
    private static final Map<BlockState, String[]> shapes = new HashMap<>();
    private static final Set<String> exported = new HashSet<>();

    private TextureExport() {}

    public static void setBeamNGUserFolder(String path) {
        Path p = Path.of(path.trim());
        if (!p.equals(root)) {
            root = p;
            exported.clear();
            shapes.clear();
            models.clear();
            BeamCraftClient.LOG.info("BeamCraft: exporting block textures to {}", p.resolve("art/beamcraft"));
        }
    }

    /** {shape, top, side, bottom} for this block, or null if textures aren't available. */
    public static String[] shapeFor(MinecraftClient mc, BlockState state) {
        if (root == null) return null;
        String[] cached = shapes.get(state);
        if (cached != null) return cached;
        try {
            BakedModel model = mc.getBlockRenderManager().getModel(state);
            String top = texture(mc, state, model, Direction.UP);
            String side = texture(mc, state, model, Direction.NORTH);
            String bottom = texture(mc, state, model, Direction.DOWN);
            if (top == null || side == null || bottom == null) return null;
            String shape = "s" + Integer.toHexString((top + "|" + side + "|" + bottom).hashCode());
            Path dae = root.resolve("art/beamcraft/gen/" + shape + ".dae");
            if (!Files.exists(dae)) {
                Files.createDirectories(dae.getParent());
                Files.writeString(dae, cubeDae(shape, "bct_" + top, "bct_" + side, "bct_" + bottom), StandardCharsets.UTF_8);
            }
            String[] result = {shape, top, side, bottom};
            shapes.put(state, result);
            return result;
        } catch (IOException | RuntimeException e) {
            BeamCraftClient.LOG.warn("BeamCraft: no textures for {}: {}", state, e.toString());
            return null;
        }
    }

    private static final Map<BlockState, String[]> models = new HashMap<>();

    /**
     * The block's real Minecraft model (stairs, slabs, fences, walls, doors...) as a BeamNG mesh:
     * {shape, texture...}, with the mesh covering the block cell (0..1). Null if the block has no
     * baked model (e.g. chests, which Minecraft draws with a special renderer).
     */
    public static String[] modelFor(MinecraftClient mc, BlockState state) {
        if (root == null) return null;
        if (models.containsKey(state)) return models.get(state);
        String[] result = null;
        try {
            BakedModel model = mc.getBlockRenderManager().getModel(state);
            List<BakedQuad> quads = new java.util.ArrayList<>();
            for (Direction d : Direction.values()) quads.addAll(model.getQuads(state, d, Random.create(42L)));
            quads.addAll(model.getQuads(state, null, Random.create(42L)));
            if (!quads.isEmpty()) {
                Map<String, List<float[]>> groups = new java.util.LinkedHashMap<>(); // material -> quads
                for (BakedQuad q : quads) {
                    String tex = spriteTexture(mc, state, q);
                    float[] quad = new float[20]; // 4 x (x, y, z, u, v) in BeamNG block-local space
                    int[] v = q.getVertexData();
                    float du = q.getSprite().getMaxU() - q.getSprite().getMinU();
                    float dv = q.getSprite().getMaxV() - q.getSprite().getMinV();
                    for (int i = 0; i < 4; i++) {
                        int b = i * 8;
                        float x = Float.intBitsToFloat(v[b]), y = Float.intBitsToFloat(v[b + 1]), z = Float.intBitsToFloat(v[b + 2]);
                        float u = (Float.intBitsToFloat(v[b + 4]) - q.getSprite().getMinU()) / du;
                        float w = (Float.intBitsToFloat(v[b + 5]) - q.getSprite().getMinV()) / dv;
                        // Minecraft (x east, y up, z south) -> BeamNG (x east, y north, z up); a rotation, so faces keep their winding
                        quad[i * 5] = x;
                        quad[i * 5 + 1] = 1 - z;
                        quad[i * 5 + 2] = y;
                        quad[i * 5 + 3] = u;
                        quad[i * 5 + 4] = 1 - w; // Collada's v runs bottom-up
                    }
                    groups.computeIfAbsent(tex, k -> new java.util.ArrayList<>()).add(quad);
                }
                String shape = "m" + Integer.toHexString(state.toString().hashCode());
                Path dae = root.resolve("art/beamcraft/gen/" + shape + ".dae");
                Files.createDirectories(dae.getParent());
                Files.writeString(dae, modelDae(shape, groups), StandardCharsets.UTF_8);
                result = new String[groups.size() + 1];
                result[0] = shape;
                int i = 1;
                for (String tex : groups.keySet()) result[i++] = tex;
            }
        } catch (IOException | RuntimeException e) {
            BeamCraftClient.LOG.warn("BeamCraft: no model for {}: {}", state, e.toString());
        }
        models.put(state, result);
        return result;
    }

    /** Texture name for one quad's sprite (exported on first use), tinted like Minecraft would. */
    private static String spriteTexture(MinecraftClient mc, BlockState state, BakedQuad quad) throws IOException {
        Identifier sprite = quad.getSprite().getContents().getId();
        int tint = -1;
        if (quad.hasColor()) {
            try {
                tint = mc.getBlockColors().getColor(state, null, null, quad.getColorIndex());
            } catch (RuntimeException e) {
                tint = -1;
            }
        }
        return exportedName(mc, sprite, tint);
    }

    private static String exportedName(MinecraftClient mc, Identifier sprite, int tint) throws IOException {
        String name = (sprite.getNamespace() + "_" + sprite.getPath()).replaceAll("[^a-z0-9_]", "_")
                + (tint != -1 ? "_" + String.format(Locale.ROOT, "%06x", tint & 0xffffff) : "");
        if (!exported.contains(name)) {
            export(mc, sprite, tint, name);
            exported.add(name);
        }
        return name;
    }

    private static String modelDae(String id, Map<String, List<float[]>> groups) {
        StringBuilder pos = new StringBuilder(), nrm = new StringBuilder(), uv = new StringBuilder();
        StringBuilder polys = new StringBuilder(), effects = new StringBuilder(), materials = new StringBuilder(), binds = new StringBuilder();
        int vert = 0, face = 0, g = 0;
        for (Map.Entry<String, List<float[]>> e : groups.entrySet()) {
            String mat = "bct_" + e.getKey();
            StringBuilder p = new StringBuilder(), vc = new StringBuilder();
            for (float[] q : e.getValue()) {
                for (int i = 0; i < 4; i++) {
                    pos.append(fmt(q[i * 5])).append(' ').append(fmt(q[i * 5 + 1])).append(' ').append(fmt(q[i * 5 + 2])).append(' ');
                    uv.append(fmt(q[i * 5 + 3])).append(' ').append(fmt(q[i * 5 + 4])).append(' ');
                }
                // face normal from the first three corners
                float ax = q[5] - q[0], ay = q[6] - q[1], az = q[7] - q[2];
                float bx = q[10] - q[0], by = q[11] - q[1], bz = q[12] - q[2];
                float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len < 1e-9f) len = 1;
                nrm.append(fmt(nx / len)).append(' ').append(fmt(ny / len)).append(' ').append(fmt(nz / len)).append(' ');
                vc.append("4 ");
                for (int i = 0; i < 4; i++) p.append(vert + i).append(' ').append(face).append(' ').append(vert + i).append(' ');
                vert += 4;
                face++;
            }
            polys.append("<polylist count=\"").append(e.getValue().size()).append("\" material=\"m").append(g)
                 .append("\"><input offset=\"0\" semantic=\"VERTEX\" source=\"#").append(id).append("-vtx\"/>")
                 .append("<input offset=\"1\" semantic=\"NORMAL\" source=\"#").append(id).append("-nrm\"/>")
                 .append("<input offset=\"2\" semantic=\"TEXCOORD\" source=\"#").append(id).append("-uv\" set=\"0\"/>")
                 .append("<vcount>").append(vc.toString().trim()).append("</vcount><p>").append(p.toString().trim())
                 .append("</p></polylist>\n");
            effects.append("<effect id=\"fx").append(g).append("\"><profile_COMMON><technique sid=\"c\"><lambert>")
                   .append("<diffuse><color>1 1 1 1</color></diffuse></lambert></technique></profile_COMMON></effect>\n");
            materials.append("<material id=\"mat").append(g).append("\" name=\"").append(mat)
                     .append("\"><instance_effect url=\"#fx").append(g).append("\"/></material>\n");
            binds.append("<instance_material symbol=\"m").append(g).append("\" target=\"#mat").append(g).append("\"/>");
            g++;
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<COLLADA xmlns=\"http://www.collada.org/2005/11/COLLADASchema\" version=\"1.4.1\">\n"
            + "<asset><unit meter=\"1\" name=\"meter\"/><up_axis>Z_UP</up_axis></asset>\n"
            + "<library_effects>\n" + effects + "</library_effects>\n"
            + "<library_materials>\n" + materials + "</library_materials>\n"
            + "<library_geometries><geometry id=\"" + id + "-mesh\" name=\"" + id + "\"><mesh>\n"
            + "<source id=\"" + id + "-pos\"><float_array id=\"" + id + "-pos-a\" count=\"" + vert * 3 + "\">" + pos.toString().trim() + "</float_array>"
            + "<technique_common><accessor source=\"#" + id + "-pos-a\" count=\"" + vert + "\" stride=\"3\"><param name=\"X\" type=\"float\"/><param name=\"Y\" type=\"float\"/><param name=\"Z\" type=\"float\"/></accessor></technique_common></source>\n"
            + "<source id=\"" + id + "-nrm\"><float_array id=\"" + id + "-nrm-a\" count=\"" + face * 3 + "\">" + nrm.toString().trim() + "</float_array>"
            + "<technique_common><accessor source=\"#" + id + "-nrm-a\" count=\"" + face + "\" stride=\"3\"><param name=\"X\" type=\"float\"/><param name=\"Y\" type=\"float\"/><param name=\"Z\" type=\"float\"/></accessor></technique_common></source>\n"
            + "<source id=\"" + id + "-uv\"><float_array id=\"" + id + "-uv-a\" count=\"" + vert * 2 + "\">" + uv.toString().trim() + "</float_array>"
            + "<technique_common><accessor source=\"#" + id + "-uv-a\" count=\"" + vert + "\" stride=\"2\"><param name=\"S\" type=\"float\"/><param name=\"T\" type=\"float\"/></accessor></technique_common></source>\n"
            + "<vertices id=\"" + id + "-vtx\"><input semantic=\"POSITION\" source=\"#" + id + "-pos\"/></vertices>\n"
            + polys
            + "</mesh></geometry></library_geometries>\n"
            + "<library_visual_scenes><visual_scene id=\"Scene\" name=\"Scene\"><node id=\"" + id + "_a2\" name=\"" + id + "_a2\" type=\"NODE\">"
            + "<instance_geometry url=\"#" + id + "-mesh\"><bind_material><technique_common>" + binds
            + "</technique_common></bind_material></instance_geometry></node></visual_scene></library_visual_scenes>\n"
            + "<scene><instance_visual_scene url=\"#Scene\"/></scene>\n</COLLADA>\n";
    }

    private static String texture(MinecraftClient mc, BlockState state, BakedModel model, Direction dir) throws IOException {
        Random random = Random.create(42L);
        List<BakedQuad> quads = model.getQuads(state, dir, random);
        if (quads.isEmpty()) quads = model.getQuads(state, null, random);
        Identifier sprite;
        int tint = -1;
        if (quads.isEmpty()) {
            sprite = model.getParticleSprite().getContents().getId();
        } else {
            BakedQuad quad = quads.get(0);
            sprite = quad.getSprite().getContents().getId();
            if (quad.hasColor()) {
                try {
                    tint = mc.getBlockColors().getColor(state, null, null, quad.getColorIndex());
                } catch (RuntimeException e) {
                    tint = -1; // colour depends on the world (e.g. water); leave untinted
                }
            }
        }
        String name = (sprite.getNamespace() + "_" + sprite.getPath()).replaceAll("[^a-z0-9_]", "_")
                + (tint != -1 ? "_" + String.format(Locale.ROOT, "%06x", tint & 0xffffff) : "");
        if (!exported.contains(name)) {
            export(mc, sprite, tint, name);
            exported.add(name);
        }
        return name;
    }

    private static void export(MinecraftClient mc, Identifier sprite, int tint, String name) throws IOException {
        Path png = root.resolve("art/beamcraft/tex/" + name + ".png");
        Path json = root.resolve("art/beamcraft/tex/" + name + ".materials.json");
        if (Files.exists(png) && Files.exists(json)) return;
        Files.createDirectories(png.getParent());
        Identifier file = Identifier.of(sprite.getNamespace(), "textures/" + sprite.getPath() + ".png");
        Resource resource = mc.getResourceManager().getResource(file).orElseThrow(() -> new IOException("missing " + file));
        try (InputStream in = resource.getInputStream(); NativeImage src = NativeImage.read(in);
             NativeImage out = new NativeImage(SIZE, SIZE, false)) {
            int w = src.getWidth();
            int h = Math.min(src.getHeight(), w); // animated textures are vertical strips: use frame 0
            int tr = (tint >> 16) & 255, tg = (tint >> 8) & 255, tb = tint & 255;
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int c = src.getColor(x * w / SIZE, y * h / SIZE); // ABGR
                    if (tint != -1) {
                        int a = (c >>> 24) & 255, b = (c >> 16) & 255, g = (c >> 8) & 255, r = c & 255;
                        c = (a << 24) | ((b * tb / 255) << 16) | ((g * tg / 255) << 8) | (r * tr / 255);
                    }
                    out.setColor(x, y, c);
                }
            }
            out.writeTo(png);
        }
        String mat = "bct_" + name;
        Files.writeString(json, "{\"" + mat + "\":{\"name\":\"" + mat + "\",\"mapTo\":\"" + mat
                + "\",\"class\":\"Material\",\"Stages\":[{\"colorMap\":\"/art/beamcraft/tex/" + name
                + ".png\",\"specularPower\":1},{},{},{}],\"alphaTest\":true,\"alphaRef\":64,"
                + "\"materialTag0\":\"beamng\",\"materialTag1\":\"BeamCraft\"}}", StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ cube shape

    /** Unit cube [0,1]^3 (Z up) with separate materials for top, sides and bottom. */
    private static String cubeDae(String id, String topMat, String sideMat, String bottomMat) {
        // faces: top, bottom, +x, -x, +y, -y. Corners go counter-clockwise seen from outside,
        // starting bottom-left so v=0 is the bottom of the texture on side faces.
        float[][][] faces = {
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
            {{0, 1, 0}, {1, 1, 0}, {1, 0, 0}, {0, 0, 0}},
            {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
            {{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}},
            {{1, 1, 0}, {0, 1, 0}, {0, 1, 1}, {1, 1, 1}},
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
        };
        float[][] normals = {{0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        StringBuilder pos = new StringBuilder(), nrm = new StringBuilder();
        for (int f = 0; f < 6; f++) {
            for (float[] v : faces[f]) pos.append(fmt(v[0])).append(' ').append(fmt(v[1])).append(' ').append(fmt(v[2])).append(' ');
            nrm.append(fmt(normals[f][0])).append(' ').append(fmt(normals[f][1])).append(' ').append(fmt(normals[f][2])).append(' ');
        }
        String uv = "0 0 1 0 1 1 0 1";
        String[] mats = {topMat, bottomMat, sideMat};
        int[][] faceGroups = {{0}, {1}, {2, 3, 4, 5}};
        StringBuilder polys = new StringBuilder();
        for (int g = 0; g < 3; g++) {
            StringBuilder p = new StringBuilder(), vc = new StringBuilder();
            for (int f : faceGroups[g]) {
                vc.append("4 ");
                for (int i = 0; i < 4; i++) p.append(f * 4 + i).append(' ').append(f).append(' ').append(i).append(' ');
            }
            polys.append("<polylist count=\"").append(faceGroups[g].length).append("\" material=\"m").append(g)
                 .append("\"><input offset=\"0\" semantic=\"VERTEX\" source=\"#").append(id).append("-vtx\"/>")
                 .append("<input offset=\"1\" semantic=\"NORMAL\" source=\"#").append(id).append("-nrm\"/>")
                 .append("<input offset=\"2\" semantic=\"TEXCOORD\" source=\"#").append(id).append("-uv\" set=\"0\"/>")
                 .append("<vcount>").append(vc.toString().trim()).append("</vcount><p>").append(p.toString().trim())
                 .append("</p></polylist>\n");
        }
        StringBuilder effects = new StringBuilder(), materials = new StringBuilder(), binds = new StringBuilder();
        for (int g = 0; g < 3; g++) {
            effects.append("<effect id=\"").append(mats[g]).append("-fx").append(g).append("\"><profile_COMMON><technique sid=\"c\"><lambert>")
                   .append("<diffuse><color>1 1 1 1</color></diffuse></lambert></technique></profile_COMMON></effect>\n");
            materials.append("<material id=\"").append(mats[g]).append("-mat").append(g).append("\" name=\"").append(mats[g])
                     .append("\"><instance_effect url=\"#").append(mats[g]).append("-fx").append(g).append("\"/></material>\n");
            binds.append("<instance_material symbol=\"m").append(g).append("\" target=\"#").append(mats[g]).append("-mat").append(g).append("\"/>");
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<COLLADA xmlns=\"http://www.collada.org/2005/11/COLLADASchema\" version=\"1.4.1\">\n"
            + "<asset><unit meter=\"1\" name=\"meter\"/><up_axis>Z_UP</up_axis></asset>\n"
            + "<library_effects>\n" + effects + "</library_effects>\n"
            + "<library_materials>\n" + materials + "</library_materials>\n"
            + "<library_geometries><geometry id=\"" + id + "-mesh\" name=\"" + id + "\"><mesh>\n"
            + "<source id=\"" + id + "-pos\"><float_array id=\"" + id + "-pos-a\" count=\"72\">" + pos.toString().trim() + "</float_array>"
            + "<technique_common><accessor source=\"#" + id + "-pos-a\" count=\"24\" stride=\"3\"><param name=\"X\" type=\"float\"/><param name=\"Y\" type=\"float\"/><param name=\"Z\" type=\"float\"/></accessor></technique_common></source>\n"
            + "<source id=\"" + id + "-nrm\"><float_array id=\"" + id + "-nrm-a\" count=\"18\">" + nrm.toString().trim() + "</float_array>"
            + "<technique_common><accessor source=\"#" + id + "-nrm-a\" count=\"6\" stride=\"3\"><param name=\"X\" type=\"float\"/><param name=\"Y\" type=\"float\"/><param name=\"Z\" type=\"float\"/></accessor></technique_common></source>\n"
            + "<source id=\"" + id + "-uv\"><float_array id=\"" + id + "-uv-a\" count=\"8\">" + uv + "</float_array>"
            + "<technique_common><accessor source=\"#" + id + "-uv-a\" count=\"4\" stride=\"2\"><param name=\"S\" type=\"float\"/><param name=\"T\" type=\"float\"/></accessor></technique_common></source>\n"
            + "<vertices id=\"" + id + "-vtx\"><input semantic=\"POSITION\" source=\"#" + id + "-pos\"/></vertices>\n"
            + polys
            + "</mesh></geometry></library_geometries>\n"
            + "<library_visual_scenes><visual_scene id=\"Scene\" name=\"Scene\"><node id=\"" + id + "_a2\" name=\"" + id + "_a2\" type=\"NODE\">"
            + "<instance_geometry url=\"#" + id + "-mesh\"><bind_material><technique_common>" + binds
            + "</technique_common></bind_material></instance_geometry></node></visual_scene></library_visual_scenes>\n"
            + "<scene><instance_visual_scene url=\"#Scene\"/></scene>\n</COLLADA>\n";
    }

    private static String fmt(float f) {
        return f == (int) f ? Integer.toString((int) f) : Float.toString(f);
    }
}
