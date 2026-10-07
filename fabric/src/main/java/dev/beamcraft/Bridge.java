package dev.beamcraft;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Localhost UDP link to the BeamNG side. Messages are plain text lines; one datagram can carry
 * several lines. Minecraft listens on {@link #MC_PORT}, BeamNG listens on {@link #BNG_PORT}.
 */
public final class Bridge {
    public static final int MC_PORT = 47820;
    public static final int BNG_PORT = 47821;
    /** Keep datagrams under LuaSocket's 8192-byte receive limit. */
    private static final int MAX_DATAGRAM = 7000;
    private static final long TIMEOUT_MS = 3000;

    private static final InetAddress LOCALHOST = InetAddress.getLoopbackAddress();
    private static final ConcurrentLinkedQueue<String> inbox = new ConcurrentLinkedQueue<>();
    private static final StringBuilder outbox = new StringBuilder();
    private static DatagramSocket socket;
    private static volatile long lastHeard;

    private Bridge() {}

    public static void start() {
        try {
            socket = new DatagramSocket(new InetSocketAddress(LOCALHOST, MC_PORT));
            socket.setReceiveBufferSize(1 << 20);
        } catch (IOException e) {
            BeamCraftClient.LOG.error("BeamCraft could not open UDP port {} - is another Minecraft with BeamCraft running?", MC_PORT, e);
            return;
        }
        Thread t = new Thread(Bridge::receiveLoop, "BeamCraft-UDP");
        t.setDaemon(true);
        t.start();
        BeamCraftClient.LOG.info("BeamCraft listening on 127.0.0.1:{}", MC_PORT);
    }

    private static void receiveLoop() {
        byte[] buf = new byte[65507];
        while (!socket.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                lastHeard = System.currentTimeMillis();
                String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                for (String line : text.split("\n")) {
                    if (!line.isBlank()) inbox.add(line.trim());
                }
            } catch (IOException e) {
                if (!socket.isClosed()) BeamCraftClient.LOG.warn("BeamCraft receive failed: {}", e.toString());
            }
        }
    }

    public static String poll() {
        return inbox.poll();
    }

    public static boolean connected() {
        return socket != null && System.currentTimeMillis() - lastHeard < TIMEOUT_MS;
    }

    public static void disconnect() {
        lastHeard = 0;
    }

    public static synchronized void send(String line) {
        if (socket == null) return;
        if (outbox.length() + line.length() + 1 > MAX_DATAGRAM) flush();
        outbox.append(line).append('\n');
    }

    public static synchronized void flush() {
        if (socket == null || outbox.length() == 0) return;
        byte[] data = outbox.toString().getBytes(StandardCharsets.UTF_8);
        outbox.setLength(0);
        try {
            socket.send(new DatagramPacket(data, data.length, LOCALHOST, BNG_PORT));
        } catch (IOException e) {
            // BeamNG not running yet; nothing to do.
        }
    }
}
