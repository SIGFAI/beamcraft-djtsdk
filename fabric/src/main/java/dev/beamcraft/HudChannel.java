package dev.beamcraft;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Overlay frames are big (a menu can be 100+ KB) and arrive in bursts. Over UDP, Windows dropped
 * most of a burst before BeamNG got to read it, so open menus froze. Frames therefore go over a
 * local TCP connection (BeamNG connects to 127.0.0.1:47822), which never loses data; when BeamNG
 * isn't connected to it yet, the old UDP path is used.
 */
public final class HudChannel {
    public static final int PORT = 47822;
    private static final Object lock = new Object();
    private static volatile Socket client;
    private static OutputStream out;

    private HudChannel() {}

    public static void start() {
        Thread t = new Thread(HudChannel::acceptLoop, "BeamCraft-HUD-TCP");
        t.setDaemon(true);
        t.start();
    }

    private static void acceptLoop() {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT));
            BeamCraftClient.LOG.info("BeamCraft overlay channel on 127.0.0.1:{}", PORT);
            while (true) {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                s.setSendBufferSize(1 << 20);
                synchronized (lock) {
                    closeQuietly();
                    client = s;
                    out = s.getOutputStream();
                }
                HudStream.reset(); // the new connection needs a full frame
                BeamCraftClient.LOG.info("BeamCraft: BeamNG connected to the overlay channel");
            }
        } catch (IOException e) {
            BeamCraftClient.LOG.error("BeamCraft could not open overlay port {}", PORT, e);
        }
    }

    public static boolean connected() {
        return client != null;
    }

    /** Sends lines over TCP (blocking if BeamNG is behind, which only slows the encoder threads). */
    public static boolean send(List<String> lines) {
        synchronized (lock) {
            if (out == null) return false;
            try {
                StringBuilder sb = new StringBuilder();
                for (String l : lines) sb.append(l).append('\n');
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                closeQuietly();
                return false;
            }
        }
    }

    private static void closeQuietly() {
        try {
            if (client != null) client.close();
        } catch (IOException ignored) {
            // already gone
        }
        client = null;
        out = null;
    }
}
