package net.chonkbase.chonkcraft.matchmaking;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Makes an outbound WebSocket relay look like a datagram socket to the proven game protocol.
 *
 * <p>The game still sends its exact lobby and lockstep datagrams. The five-byte relay envelope
 * says only which room endpoint should receive them. The service never decodes game traffic,
 * and a temporary relay reconnect behaves like ordinary UDP loss: newer retries carry the state
 * forward instead of a TCP backlog releasing stale commands later.
 */
public final class RelayDatagramSocket extends DatagramSocket {

    private static final byte DATA = 1;
    private static final byte ROOM_STARTED = 2;
    private static final byte ROOM_CLOSED = 3;
    private static final int ENVELOPE_BYTES = 5;
    private static final int VIRTUAL_PORT_BASE = 20_000;
    private static final int MAX_ENDPOINTS = 1_000;
    private static final int MAX_QUEUED_PACKETS = 1_024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);

    private final URI relayUri;
    private final String ticket;
    private final int endpointId;
    private final HttpClient http;
    private final ArrayBlockingQueue<Received> incoming =
            new ArrayBlockingQueue<>(MAX_QUEUED_PACKETS);
    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicInteger reconnectAttempt = new AtomicInteger();
    private final ScheduledExecutorService reconnect = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "chonkcraft-relay-reconnect");
        thread.setDaemon(true);
        return thread;
    });

    private volatile int timeoutMillis;

    private record Received(int source, byte[] bytes) {
    }

    public RelayDatagramSocket(URI relayUri, String ticket, int endpointId) throws IOException {
        super((SocketAddress) null);
        this.relayUri = Objects.requireNonNull(relayUri, "relayUri");
        this.ticket = Objects.requireNonNull(ticket, "ticket");
        this.endpointId = checkedEndpoint(endpointId);
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        connect(true);
    }

    /** A stable synthetic address used only inside this process. */
    public static InetSocketAddress addressOf(int endpointId) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(),
                VIRTUAL_PORT_BASE + checkedEndpoint(endpointId));
    }

    /** Recovers the endpoint carried by a synthetic address. */
    public static int endpointOf(SocketAddress address) {
        if (!(address instanceof InetSocketAddress inet)
                || !inet.getAddress().isLoopbackAddress()) {
            throw new IllegalArgumentException("Not a ChonkCraft relay address: " + address);
        }
        return checkedEndpoint(inet.getPort() - VIRTUAL_PORT_BASE);
    }

    private static int checkedEndpoint(int endpointId) {
        if (endpointId < 0 || endpointId >= MAX_ENDPOINTS) {
            throw new IllegalArgumentException("Relay endpoint is out of range: " + endpointId);
        }
        return endpointId;
    }

    @Override
    public void send(DatagramPacket packet) throws IOException {
        if (closed.get()) {
            throw new SocketException("Relay socket is closed");
        }
        int target = endpointOf(packet.getSocketAddress());
        ByteBuffer frame = ByteBuffer.allocate(ENVELOPE_BYTES + packet.getLength());
        frame.put(DATA).putInt(target);
        frame.put(packet.getData(), packet.getOffset(), packet.getLength()).flip();
        WebSocket active = webSocket.get();
        if (active != null && !active.isOutputClosed()) {
            active.sendBinary(frame, true);
        }
        // When reconnecting this datagram is intentionally lost. The lobby repeats state and
        // lockstep resends absent cycles; retaining it here would create stale head-of-line data.
    }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        if (closed.get()) {
            throw new SocketException("Relay socket is closed");
        }
        Received received;
        try {
            received = timeoutMillis <= 0
                    ? incoming.take()
                    : incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SocketException("Interrupted while waiting for relay data");
        }
        if (received == null) {
            throw new java.net.SocketTimeoutException("Receive timed out");
        }
        int count = Math.min(packet.getLength(), received.bytes().length);
        System.arraycopy(received.bytes(), 0, packet.getData(), packet.getOffset(), count);
        packet.setLength(count);
        InetSocketAddress source = addressOf(received.source());
        packet.setAddress(source.getAddress());
        packet.setPort(source.getPort());
    }

    @Override
    public synchronized void setSoTimeout(int timeout) throws SocketException {
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout cannot be negative");
        }
        timeoutMillis = timeout;
    }

    @Override
    public synchronized int getSoTimeout() {
        return timeoutMillis;
    }

    @Override
    public int getLocalPort() {
        return VIRTUAL_PORT_BASE + endpointId;
    }

    public boolean isRelayConnected() {
        WebSocket active = webSocket.get();
        return active != null && !active.isInputClosed() && !active.isOutputClosed();
    }

    /** Host-only lifecycle hint; it contains no simulation state. */
    public void markRoomStarted() {
        sendControl(ROOM_STARTED);
    }

    /** Host-only explicit departure, so a cancelled lobby does not linger in browsing. */
    public void closeRoom() {
        sendControl(ROOM_CLOSED);
    }

    private void sendControl(byte kind) {
        WebSocket active = webSocket.get();
        if (!closed.get() && active != null && !active.isOutputClosed()) {
            active.sendBinary(ByteBuffer.wrap(new byte[] {kind}), true);
        }
    }

    private void connect(boolean required) throws IOException {
        if (closed.get()) {
            return;
        }
        try {
            WebSocket connected = http.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .header("Authorization", "Bearer " + ticket)
                    .subprotocols("chonkcraft-relay-v1")
                    .buildAsync(relayUri, new Listener())
                    .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            WebSocket previous = webSocket.getAndSet(connected);
            if (previous != null && previous != connected) {
                previous.abort();
            }
            reconnectAttempt.set(0);
            reconnectScheduled.set(false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while connecting to the game relay", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            if (required) {
                throw new IOException("Could not connect to the game relay", failure);
            }
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (closed.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        int attempt = reconnectAttempt.getAndIncrement();
        long delay = Math.min(8, 1L << Math.min(attempt, 3));
        reconnect.schedule(() -> {
            reconnectScheduled.set(false);
            try {
                connect(false);
            } catch (IOException ignored) {
                scheduleReconnect();
            }
        }, delay, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        WebSocket active = webSocket.getAndSet(null);
        if (active != null) {
            active.sendClose(WebSocket.NORMAL_CLOSURE, "leaving");
        }
        reconnect.shutdownNow();
        incoming.clear();
        super.close();
    }

    private final class Listener implements WebSocket.Listener {
        private final ByteArrayOutputStream fragments = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            byte[] part = new byte[data.remaining()];
            data.get(part);
            fragments.writeBytes(part);
            if (last) {
                takeFrame(fragments.toByteArray());
                fragments.reset();
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) {
            socket.request(1);
            return socket.sendPong(message);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            if (webSocket.compareAndSet(socket, null)) {
                scheduleReconnect();
            }
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            if (webSocket.compareAndSet(socket, null)) {
                scheduleReconnect();
            }
        }

        private void takeFrame(byte[] frame) {
            if (frame.length < ENVELOPE_BYTES || frame[0] != DATA) {
                return;
            }
            int source = ByteBuffer.wrap(frame, 1, 4).getInt();
            if (source < 0 || source >= MAX_ENDPOINTS) {
                return;
            }
            Received packet = new Received(source, Arrays.copyOfRange(frame, ENVELOPE_BYTES,
                    frame.length));
            if (!incoming.offer(packet)) {
                incoming.poll();
                incoming.offer(packet);
            }
        }
    }
}
