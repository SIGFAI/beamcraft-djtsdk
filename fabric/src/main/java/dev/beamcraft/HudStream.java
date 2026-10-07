package dev.beamcraft;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

/**
 * Everything Minecraft draws over BeamNG (hand, Steve, HUD, inventory, chat) is captured from the
 * framebuffer and sent as PNG frames:
 * {@code hud <frame> <part> <parts> <x> <y> <w> <h> <fullW> <fullH> <base64>}.
 * Only the rectangle that actually contains something is sent.
 *
 * To keep up with BeamNG's frame rate the GPU readback is asynchronous (pixel buffer objects,
 * read one frame later) and PNG encoding runs on a small thread pool with the fastest compression.
 */
public final class HudStream {
    private static final int CHUNK = 6000; // base64 chars per datagram line

    private static final ThreadPoolExecutor encoders = new ThreadPoolExecutor(3, 3, 1, TimeUnit.MINUTES,
            new ArrayBlockingQueue<>(2), r -> {
                Thread t = new Thread(r, "BeamCraft-HUD-encoder");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final AtomicInteger frameCounter = new AtomicInteger();
    private static volatile int lastSentFrame;

    private static volatile int targetFps = 60;
    private static long lastCapture;
    private static long lastNonEmpty;
    private static boolean sentEmpty;
    // counters for the 10-second log line, so slow overlays can be diagnosed
    private static final AtomicInteger captured = new AtomicInteger(), unchanged = new AtomicInteger(), sent = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong sentBytes = new java.util.concurrent.atomic.AtomicLong();
    private static long lastStatsLog;
    private static final java.util.concurrent.atomic.AtomicLong captureNanos = new java.util.concurrent.atomic.AtomicLong();
    private static final AtomicInteger captureCalls = new AtomicInteger();
    private static long lastSum, lastSumTime;

    // A small ring of pixel-pack buffers, each with a GPU fence. Copies are started without waiting
    // and only collected once their fence says the GPU has finished, so Minecraft never stalls on a
    // GPU that is busy drawing BeamNG.
    private static final int RING = 8;
    private static final int[] pbo = new int[RING];
    private static final long[] fence = new long[RING];
    private static final long[] fenceStart = new long[RING];
    private static long latencyNanos, latencyCount;
    private static int pboW, pboH, next;

    private HudStream() {}

    public static void setTargetFps(int fps) {
        targetFps = Math.max(10, Math.min(fps, 120));
    }

    /** Forget what BeamNG has, so the next frame is sent even if nothing changed. */
    public static void reset() {
        sentEmpty = false;
        lastSum = 0;
    }

    /** Called at the end of every rendered frame while BeamNG shows the overlay (render thread). */
    public static void capture(MinecraftClient mc) {
        long t0 = System.nanoTime();
        Framebuffer fb = mc.getFramebuffer();
        int w = fb.textureWidth, h = fb.textureHeight;
        if (w <= 0 || h <= 0) return;
        if (w != pboW || h != pboH) allocate(w, h);

        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);

        // collect every copy the GPU has finished (oldest first), without ever waiting
        for (int k = 0; k < RING; k++) {
            int i = (next + k) % RING;
            if (fence[i] == 0) continue;
            int status = GL32.glClientWaitSync(fence[i], GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
            if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) continue;
            GL32.glDeleteSync(fence[i]);
            fence[i] = 0;
            latencyNanos += System.nanoTime() - fenceStart[i];
            latencyCount++;
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
            ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
            if (mapped != null) {
                byte[] pixels = new byte[w * h * 4];
                mapped.get(pixels);
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                int frame = frameCounter.incrementAndGet();
                captured.incrementAndGet();
                encoders.execute(() -> encodeAndSend(frame, pixels, w, h));
            }
        }

        // start a new copy if it's time and a buffer is free
        long now = System.nanoTime();
        // a steady schedule: with Minecraft at e.g. 90 fps this still averages exactly the target rate
        long interval = 1_000_000_000L / targetFps;
        if (now - lastCapture > 4 * interval) lastCapture = now - interval; // after a pause, don't try to catch up
        if (now - lastCapture >= interval && fence[next] == 0) {
            lastCapture += interval;
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fb.fbo);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[next]);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            fence[next] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            fenceStart[next] = now;
            GL11.glFlush(); // hand the copy to the GPU right away
            next = (next + 1) % RING;
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPack);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        captureNanos.addAndGet(System.nanoTime() - t0);
        captureCalls.incrementAndGet();

        long ms = System.currentTimeMillis();
        if (ms - lastStatsLog > 10_000) {
            if (lastStatsLog != 0) {
                int frames = captured.getAndSet(0);
                double msPerFrame = captureNanos.getAndSet(0) / 1e6 / Math.max(1, captureCalls.getAndSet(0));
                BeamCraftClient.LOG.info("BeamCraft overlay, last 10 s: Minecraft at {} fps, target {} fps, {} frames captured, {} unchanged, {} sent ({} KB), size {}x{}, {} ms capture work per frame, copy delay {} ms, {} mouse events, overlay channel {}; {}",
                        MinecraftClient.getInstance().getCurrentFps(), targetFps, frames, unchanged.getAndSet(0), sent.getAndSet(0),
                        sentBytes.getAndSet(0) / 1024, w, h, String.format(java.util.Locale.ROOT, "%.2f", msPerFrame), latencyCount == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.0f", latencyNanos / 1e6 / latencyCount), BeamCraftClient.mouseEvents.getAndSet(0), HudChannel.connected() ? "TCP" : "UDP", FrameTiming.summary());
                latencyNanos = 0;
                latencyCount = 0;
            }
            lastStatsLog = ms;
        }
    }

    private static void allocate(int w, int h) {
        for (int i = 0; i < RING; i++) {
            if (fence[i] != 0) {
                GL32.glDeleteSync(fence[i]);
                fence[i] = 0;
            }
            if (pbo[i] == 0) pbo[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) w * h * 4, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        pboW = w;
        pboH = h;
        next = 0;
    }

    // ------------------------------------------------------------------ encoder threads

    private static void encodeAndSend(int frame, byte[] px, int w, int h) {
        try {
            // bounding box of everything that isn't fully transparent (rows come bottom-up from GL)
            int minX = w, maxX = -1, minY = h, maxY = -1;
            for (int glRow = 0; glRow < h; glRow++) {
                int base = glRow * w * 4 + 3;
                int first = -1, last = -1;
                for (int x = 0; x < w; x++) {
                    if (px[base + x * 4] != 0) {
                        if (first < 0) first = x;
                        last = x;
                    }
                }
                if (first >= 0) {
                    int y = h - 1 - glRow;
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    minX = Math.min(minX, first);
                    maxX = Math.max(maxX, last);
                }
            }
            if (frame < lastSentFrame) return; // a newer frame already went out
            if (maxX < 0) {
                // nothing visible: tell BeamNG once, then stay quiet
                long now = System.currentTimeMillis();
                if (sentEmpty && now - lastNonEmpty < 1000) return;
                sentEmpty = true;
                lastNonEmpty = now;
                lastSentFrame = frame;
                sendLines(List.of("hud " + frame + " 0 1 0 0 0 0 " + w + " " + h + " -"));
                return;
            }
            sentEmpty = false;
            int cw = maxX - minX + 1, ch = maxY - minY + 1;
            // identical to what BeamNG already shows (static hotbar, open inventory)? don't resend
            CRC32 crc = new CRC32();
            for (int y = 0; y < ch; y++) crc.update(px, ((h - 1 - (minY + y)) * w + minX) * 4, cw * 4);
            long sum = crc.getValue() ^ ((long) minX << 48) ^ ((long) minY << 32) ^ ((long) cw << 16) ^ ch;
            long nowMs = System.currentTimeMillis();
            synchronized (HudStream.class) {
                if (sum == lastSum && nowMs - lastSumTime < 1000) {
                    unchanged.incrementAndGet();
                    return;
                }
                lastSum = sum;
                lastSumTime = nowMs;
            }
            byte[] png = encodePng(px, w, h, minX, minY, cw, ch);
            if (frame < lastSentFrame) return;
            lastSentFrame = frame;
            String b64 = Base64.getEncoder().encodeToString(png);
            sent.incrementAndGet();
            sentBytes.addAndGet(b64.length());
            int parts = (b64.length() + CHUNK - 1) / CHUNK;
            String head = " " + minX + " " + minY + " " + cw + " " + ch + " " + w + " " + h + " ";
            List<String> lines = new java.util.ArrayList<>(parts);
            for (int i = 0; i < parts; i++) {
                lines.add("hud " + frame + " " + i + " " + parts + head
                        + b64.substring(i * CHUNK, Math.min(b64.length(), (i + 1) * CHUNK)));
            }
            sendLines(lines);
        } catch (RuntimeException e) {
            BeamCraftClient.LOG.warn("BeamCraft HUD encode failed: {}", e.toString());
        }
    }

    /** Over the TCP overlay channel when BeamNG is connected to it, else (slower, lossy) UDP. */
    private static void sendLines(List<String> lines) {
        if (HudChannel.send(lines)) return;
        for (int i = 0; i < lines.size(); i++) {
            Bridge.send(lines.get(i));
            if (i % 8 == 7) {
                Bridge.flush();
                try { Thread.sleep(1); } catch (InterruptedException ignored) { } // let BeamNG keep up
            }
        }
        Bridge.flush();
    }

    /** Minimal, fast PNG writer (RGBA, no row filters, fastest deflate) for a crop of a GL image. */
    private static byte[] encodePng(byte[] px, int w, int h, int x0, int y0, int cw, int ch) {
        // Each row is stored as its difference to the pixel on the left ("Sub") or the row above
        // ("Up"), whichever looks smaller. Minecraft's flat-coloured menus then compress several
        // times better than raw pixels, even at the fastest deflate setting.
        int stride = cw * 4;
        byte[] raw = new byte[ch * (stride + 1)];
        byte[] sub = new byte[stride], up = new byte[stride];
        int o = 0;
        for (int y = 0; y < ch; y++) {
            int src = ((h - 1 - (y0 + y)) * w + x0) * 4;
            int prev = ((h - (y0 + y)) * w + x0) * 4; // the row above in image order (GL rows run bottom-up)
            long sumSub = 0, sumUp = 0;
            for (int i = 0; i < stride; i++) {
                int cur = px[src + i];
                byte s = (byte) (cur - (i >= 4 ? px[src + i - 4] : 0));
                byte u = (byte) (cur - (y > 0 ? px[prev + i] : 0));
                sub[i] = s;
                up[i] = u;
                sumSub += Math.abs(s);
                sumUp += Math.abs(u);
            }
            boolean useUp = y > 0 && sumUp < sumSub;
            raw[o++] = (byte) (useUp ? 2 : 1);
            System.arraycopy(useUp ? up : sub, 0, raw, o, stride);
            o += stride;
        }
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream idat = new ByteArrayOutputStream(raw.length / 4 + 64);
        byte[] buf = new byte[65536];
        while (!deflater.finished()) idat.write(buf, 0, deflater.deflate(buf));
        deflater.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream(idat.size() + 64);
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, cw);
        putInt(ihdr, 4, ch);
        ihdr[8] = 8;  // bit depth
        ihdr[9] = 6;  // RGBA
        chunk(out, "IHDR", ihdr);
        chunk(out, "IDAT", idat.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.writeBytes(len);
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.writeBytes(c);
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }
}
