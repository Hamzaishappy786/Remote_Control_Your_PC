package com.example.wifiremotecontrol;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Zero-backlog UDP sender.
 *
 * High-frequency motion packets (MOVE, SCROLL) are stored in a single atomic slot.
 * If a new one arrives before the previous is sent, the old one is silently dropped —
 * only the freshest position is ever transmitted, so latency never accumulates.
 *
 * Low-frequency events (CLICK, KEY, HOTKEY, MOUSEDOWN/UP) go through a small
 * bounded queue so they are never dropped.
 */
public class UdpSender {

    public static final int PORT = 5005;

    private DatagramSocket socket;

    // Resolved once when the host is set; avoids DNS on every send()
    private final AtomicReference<InetAddress> cachedAddr = new AtomicReference<>();

    // Latest-wins slot for rapid MOVE / SCROLL packets
    private final AtomicReference<byte[]> motionSlot = new AtomicReference<>();

    // FIFO queue for click / key / hotkey events — never dropped
    private final LinkedBlockingQueue<byte[]> eventQueue = new LinkedBlockingQueue<>(256);

    private volatile boolean running = true;
    private final Thread networkThread;

    public UdpSender() {
        try {
            socket = new DatagramSocket();
        } catch (Exception e) {
            e.printStackTrace();
        }

        networkThread = new Thread(() -> {
            while (running) {
                // 1. Always drain the motion slot first — freshest packet only
                byte[] motion = motionSlot.getAndSet(null);
                if (motion != null) dispatch(motion);

                // 2. Drain one event packet
                byte[] event = eventQueue.poll();
                if (event != null) dispatch(event);

                // 3. If both slots were empty, yield briefly to avoid busy-wait
                if (motion == null && event == null) {
                    // 200 µs — responsive enough for interactive use, cheap on CPU
                    try { Thread.sleep(0, 200_000); } catch (InterruptedException ignored) {}
                }
            }
        });
        networkThread.setName("UdpSender");
        networkThread.setDaemon(true);
        networkThread.start();
    }

    /** Resolves and caches the host address. Call this as early as possible. */
    public void setHost(String host) {
        try {
            cachedAddr.set(InetAddress.getByName(host));
        } catch (Exception ignored) {}
    }

    /** Enqueues a packet for immediate dispatch. Non-blocking, never throws. */
    public void send(String host, String message) {
        if (cachedAddr.get() == null) setHost(host);

        byte[] data;
        try {
            data = message.getBytes(StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return;
        }

        // MOVE only: overwrite the slot so stale positions are dropped
        // SCROLL goes through the event queue — scroll events must never be dropped
        if (message.startsWith("MOVE,")) {
            motionSlot.set(data);
        } else {
            eventQueue.offer(data);
        }
    }

    private void dispatch(byte[] data) {
        InetAddress addr = cachedAddr.get();
        if (addr == null || socket == null || socket.isClosed()) return;
        try {
            socket.send(new DatagramPacket(data, data.length, addr, PORT));
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        running = false;
        networkThread.interrupt();
        if (socket != null && !socket.isClosed()) socket.close();
    }
}
