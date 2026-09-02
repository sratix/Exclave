/*
 * Copyright (C) 2026 by Exclave contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package network.exclave.udpgw;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Desktop counterpart of Exclave's in-app UDP gateway.
 *
 * It publishes a SOCKS5 endpoint that speaks both TCP and UDP. TCP is relayed straight to an
 * upstream SOCKS5 proxy (typically `ssh -D`), while UDP is re-framed with the badvpn udpgw wire
 * format and multiplexed over a single TCP connection to a udpgw server reached through that same
 * proxy, so a TCP-only tunnel still carries gaming, VoIP and DNS traffic:
 *
 *     app --socks5/tcp--> bridge --socks5--> ssh -D --> internet
 *     app --socks5/udp--> bridge --udpgw/tcp--> ssh -D --> udpgw server --> internet
 *
 * The wire format, taken from badvpn's protocol/udpgw.h, is a length-prefixed stream of packets:
 *
 *     [length: u16le][flags: u8][conid: u16le][addr][payload]
 *
 * where `length` counts every byte after itself, `addr` is 6 bytes (IPv4 + port) or 18 bytes (IPv6
 * + port) in network byte order, and keepalive packets carry neither `addr` nor payload.
 */
public final class UdpgwBridge {

    private static final int FLAG_KEEPALIVE = 1;
    private static final int FLAG_REBIND = 1 << 1;
    private static final int FLAG_IPV6 = 1 << 3;

    private static final int MAX_PACKET_SIZE = 65535;
    private static final long KEEPALIVE_INTERVAL_MS = 10_000L;
    private static final long RECONNECT_DELAY_MS = 1_000L;
    private static final long RECONNECT_DELAY_MAX_MS = 16_000L;

    private final InetSocketAddress listenAddress;
    private final InetSocketAddress upstreamSocks;
    private final String udpgwHost;
    private final int udpgwPort;
    private final int maxConnections;

    private final BlockingQueue<byte[]> outgoing = new ArrayBlockingQueue<>(256);
    private final AtomicReference<Socket> tunnelSocket = new AtomicReference<>();

    private final Object stateLock = new Object();
    private final LinkedHashMap<InetSocketAddress, Integer> clientToConId =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Integer, InetSocketAddress> conIdToClient = new HashMap<>();
    private final Set<Integer> rebindPending = new HashSet<>();
    private int nextConId;

    private volatile DatagramSocket udpSocket;
    private volatile boolean closed;

    private UdpgwBridge(InetSocketAddress listenAddress, InetSocketAddress upstreamSocks,
                        String udpgwHost, int udpgwPort, int maxConnections) {
        this.listenAddress = listenAddress;
        this.upstreamSocks = upstreamSocks;
        this.udpgwHost = udpgwHost;
        this.udpgwPort = udpgwPort;
        this.maxConnections = Math.max(1, Math.min(65535, maxConnections));
    }

    // ------------------------------------------------------------------ startup

    public static void main(String[] args) throws Exception {
        InetSocketAddress listen = parseEndpoint("127.0.0.1:1081", 0);
        InetSocketAddress upstream = parseEndpoint("127.0.0.1:1080", 0);
        String udpgwHost = "127.0.0.1";
        int udpgwPort = 7300;
        int maxConnections = 100;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-h") || arg.equals("--help")) {
                usage(System.out);
                return;
            }
            if (i + 1 >= args.length) {
                System.err.println("missing value for " + arg);
                usage(System.err);
                System.exit(2);
            }
            String value = args[++i];
            switch (arg) {
                case "--listen":
                    listen = parseEndpoint(value, 1081);
                    break;
                case "--upstream-socks":
                    upstream = parseEndpoint(value, 1080);
                    break;
                case "--udpgw": {
                    InetSocketAddress parsed = parseEndpoint(value, 7300);
                    udpgwHost = parsed.getHostString();
                    udpgwPort = parsed.getPort();
                    break;
                }
                case "--max-connections":
                    maxConnections = Integer.parseInt(value);
                    break;
                default:
                    System.err.println("unknown option " + arg);
                    usage(System.err);
                    System.exit(2);
            }
        }

        new UdpgwBridge(listen, upstream, udpgwHost, udpgwPort, maxConnections).run();
    }

    private static void usage(java.io.PrintStream out) {
        out.println("Exclave UDP gateway bridge - UDP over a TCP-only tunnel via badvpn-udpgw");
        out.println();
        out.println("  java -jar udpgw-bridge.jar [options]");
        out.println();
        out.println("  --listen <host:port>          SOCKS5 endpoint to publish   (default 127.0.0.1:1081)");
        out.println("  --upstream-socks <host:port>  SOCKS5 proxy to tunnel through, e.g. ssh -D");
        out.println("                                                              (default 127.0.0.1:1080)");
        out.println("  --udpgw <host:port>           udpgw server as seen from the tunnel exit");
        out.println("                                                              (default 127.0.0.1:7300)");
        out.println("  --max-connections <n>         concurrent UDP sessions       (default 100)");
        out.println();
        out.println("Typical use:");
        out.println("  ssh -N -D 1080 user@server            # server runs badvpn-udpgw on 127.0.0.1:7300");
        out.println("  java -jar udpgw-bridge.jar            # then point applications at 127.0.0.1:1081");
    }

    private static InetSocketAddress parseEndpoint(String value, int defaultPort) {
        String host;
        int port = defaultPort;
        int split = value.lastIndexOf(':');
        if (value.startsWith("[")) { // [::1]:port
            int end = value.indexOf(']');
            host = value.substring(1, end);
            if (end + 1 < value.length() && value.charAt(end + 1) == ':') {
                port = Integer.parseInt(value.substring(end + 2));
            }
        } else if (split >= 0 && value.indexOf(':') == split) {
            host = value.substring(0, split);
            port = Integer.parseInt(value.substring(split + 1));
        } else {
            host = value;
        }
        if (port <= 0) throw new IllegalArgumentException("missing port in " + value);
        return new InetSocketAddress(host, port);
    }

    private void run() throws IOException {
        // SO_REUSEADDR is left at the platform default on purpose. Java already enables it for a
        // TCP listener on Unix, while Windows deliberately leaves it off because there it lets an
        // unrelated process bind the same address and silently steal traffic. UDP has no TIME_WAIT,
        // so it never needs the flag to rebind after a restart.
        DatagramSocket udp = new DatagramSocket(null);
        udp.bind(listenAddress);
        udpSocket = udp;

        ServerSocket listener = new ServerSocket();
        listener.bind(listenAddress, 64);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            closed = true;
            closeQuietly(listener);
            udp.close();
            Socket tunnel = tunnelSocket.get();
            if (tunnel != null) closeQuietly(tunnel);
        }));

        daemon("udpgw-tunnel", this::tunnelLoop);
        daemon("udpgw-udp", () -> clientReadLoop(udp));

        log("listening on socks5://" + format(listenAddress)
                + ", upstream socks5://" + format(upstreamSocks)
                + ", udpgw " + udpgwHost + ":" + udpgwPort);

        while (!closed) {
            Socket client;
            try {
                client = listener.accept();
            } catch (IOException e) {
                if (!closed) log("accept failed: " + e);
                return;
            }
            daemon("socks-client", () -> handleClient(client));
        }
    }

    // -------------------------------------------------------------------- SOCKS5

    private void handleClient(Socket client) {
        try {
            client.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();

            if (in.readUnsignedByte() != 5) return;
            int methodCount = in.readUnsignedByte();
            in.readFully(new byte[methodCount]);
            out.write(new byte[]{5, 0}); // no authentication
            out.flush();

            if (in.readUnsignedByte() != 5) return;
            int command = in.readUnsignedByte();
            in.readUnsignedByte(); // RSV
            String host = readAddress(in);
            int port = in.readUnsignedShort();

            switch (command) {
                case 1: // CONNECT
                    relayTcp(client, out, host, port);
                    return;
                case 3: // UDP ASSOCIATE
                    replyUdpAssociate(out);
                    // The association lives as long as this control connection does.
                    while (!closed && in.read() >= 0) { /* idle */ }
                    return;
                default:
                    out.write(new byte[]{5, 7, 0, 1, 0, 0, 0, 0, 0, 0}); // command not supported
                    out.flush();
            }
        } catch (IOException e) {
            // A client hanging up mid-handshake is routine.
        } finally {
            closeQuietly(client);
        }
    }

    private static String readAddress(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        switch (type) {
            case 1: {
                byte[] raw = new byte[4];
                in.readFully(raw);
                return InetAddress.getByAddress(raw).getHostAddress();
            }
            case 3: {
                byte[] raw = new byte[in.readUnsignedByte()];
                in.readFully(raw);
                return new String(raw, java.nio.charset.StandardCharsets.US_ASCII);
            }
            case 4: {
                byte[] raw = new byte[16];
                in.readFully(raw);
                return InetAddress.getByAddress(raw).getHostAddress();
            }
            default:
                throw new SocketException("unknown SOCKS5 address type " + type);
        }
    }

    private void replyUdpAssociate(OutputStream out) throws IOException {
        InetAddress bind = listenAddress.getAddress();
        byte[] raw = bind == null ? new byte[]{127, 0, 0, 1} : bind.getAddress();
        byte[] reply = new byte[6 + raw.length];
        reply[0] = 5;
        reply[1] = 0; // succeeded
        reply[3] = (byte) (raw.length == 4 ? 1 : 4);
        System.arraycopy(raw, 0, reply, 4, raw.length);
        int port = listenAddress.getPort();
        reply[4 + raw.length] = (byte) (port >>> 8);
        reply[5 + raw.length] = (byte) port;
        out.write(reply);
        out.flush();
    }

    /** TCP is not our business; hand it to the upstream proxy and shovel bytes both ways. */
    private void relayTcp(Socket client, OutputStream clientOut, String host, int port) {
        Socket upstream;
        try {
            upstream = dialThroughUpstream(host, port);
        } catch (IOException e) {
            try {
                clientOut.write(new byte[]{5, 1, 0, 1, 0, 0, 0, 0, 0, 0}); // general failure
                clientOut.flush();
            } catch (IOException ignored) {
            }
            return;
        }
        try {
            clientOut.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
            clientOut.flush();
            Thread pump = daemon("relay-up", () -> copy(client, upstream));
            copy(upstream, client);
            pump.interrupt();
        } catch (IOException e) {
            // fall through to cleanup
        } finally {
            closeQuietly(upstream);
        }
    }

    private static void copy(Socket from, Socket to) {
        byte[] buffer = new byte[32 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // connection closed
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    /** Opens a TCP connection to host:port through the upstream SOCKS5 proxy. */
    private Socket dialThroughUpstream(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(upstreamSocks, 10_000);
        } catch (IOException e) {
            closeQuietly(socket);
            // Naming the endpoint matters here: this is what fails when the SSH tunnel is not up.
            throw new IOException("cannot reach the upstream SOCKS5 proxy at "
                    + format(upstreamSocks) + " (" + e.getMessage() + ")"
                    + " - is `ssh -D " + upstreamSocks.getPort() + "` running?", e);
        }
        socket.setTcpNoDelay(true);
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write(new byte[]{5, 1, 0}); // VER, one method, no authentication
            out.flush();
            if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 0) {
                throw new IOException("upstream proxy rejected the handshake");
            }

            byte[] literal = parseIpLiteral(host);
            List<Byte> request = new ArrayList<>();
            request.add((byte) 5);
            request.add((byte) 1); // CONNECT
            request.add((byte) 0);
            if (literal == null) {
                byte[] raw = host.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                request.add((byte) 3);
                request.add((byte) raw.length);
                for (byte b : raw) request.add(b);
            } else {
                request.add((byte) (literal.length == 4 ? 1 : 4));
                for (byte b : literal) request.add(b);
            }
            request.add((byte) (port >>> 8));
            request.add((byte) port);
            byte[] encoded = new byte[request.size()];
            for (int i = 0; i < encoded.length; i++) encoded[i] = request.get(i);
            out.write(encoded);
            out.flush();

            if (in.readUnsignedByte() != 5) throw new IOException("bad upstream reply");
            int status = in.readUnsignedByte();
            if (status != 0) {
                throw new IOException("the upstream proxy refused a connection to " + host + ":"
                        + port + ", SOCKS5 status " + status);
            }
            in.readUnsignedByte(); // RSV
            readAddress(in);
            in.readUnsignedShort();
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    /** Returns the raw bytes when the host is an IP literal, or null when it is a name. */
    private static byte[] parseIpLiteral(String host) {
        boolean looksNumeric = host.indexOf(':') >= 0;
        if (!looksNumeric) {
            looksNumeric = true;
            for (int i = 0; i < host.length(); i++) {
                char c = host.charAt(i);
                if (c != '.' && (c < '0' || c > '9')) {
                    looksNumeric = false;
                    break;
                }
            }
        }
        if (!looksNumeric) return null;
        try {
            return InetAddress.getByName(host).getAddress();
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------- UDP side

    private void clientReadLoop(DatagramSocket udp) {
        byte[] buffer = new byte[MAX_PACKET_SIZE];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                udp.receive(packet);
                forwardToTunnel(packet);
            } catch (IOException e) {
                if (!closed) log("udp receive failed: " + e);
                return;
            } catch (RuntimeException e) {
                // A malformed datagram must not take the loop down.
            }
        }
    }

    private void forwardToTunnel(DatagramPacket packet) throws IOException {
        byte[] data = packet.getData();
        int end = packet.getOffset() + packet.getLength();
        int offset = packet.getOffset();
        if (packet.getLength() < 10) return;

        offset += 2; // RSV
        if (data[offset++] != 0) return; // fragmented datagrams are not supported

        InetAddress destination;
        int type = data[offset++] & 0xff;
        if (type == 1) {
            destination = InetAddress.getByAddress(copy(data, offset, 4));
            offset += 4;
        } else if (type == 4) {
            destination = InetAddress.getByAddress(copy(data, offset, 16));
            offset += 16;
        } else if (type == 3) {
            int length = data[offset++] & 0xff;
            // udpgw addresses destinations by IP, so a name has to be resolved here.
            destination = InetAddress.getByName(
                    new String(data, offset, length, java.nio.charset.StandardCharsets.US_ASCII));
            offset += length;
        } else {
            return;
        }
        if (offset + 2 > end) return;
        int port = ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
        offset += 2;

        InetSocketAddress client = new InetSocketAddress(packet.getAddress(), packet.getPort());
        int[] acquired = acquireConId(client);
        int conId = acquired[0];

        int flags = 0;
        if (acquired[1] != 0) flags |= FLAG_REBIND;
        boolean ipv6 = destination instanceof Inet6Address;
        if (ipv6) flags |= FLAG_IPV6;

        byte[] raw = destination.getAddress();
        int payloadLength = end - offset;
        byte[] body = new byte[3 + raw.length + 2 + payloadLength];
        body[0] = (byte) flags;
        body[1] = (byte) conId;
        body[2] = (byte) (conId >>> 8);
        System.arraycopy(raw, 0, body, 3, raw.length);
        body[3 + raw.length] = (byte) (port >>> 8);
        body[4 + raw.length] = (byte) port;
        System.arraycopy(data, offset, body, 5 + raw.length, payloadLength);

        // UDP is lossy by definition; a backed up tunnel drops rather than stalling the reader.
        outgoing.offer(frame(body));
    }

    private static byte[] copy(byte[] source, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(source, offset, out, 0, length);
        return out;
    }

    /** Returns {conId, rebind}. A connection id stands for one client UDP endpoint. */
    private int[] acquireConId(InetSocketAddress client) {
        synchronized (stateLock) {
            Integer existing = clientToConId.get(client);
            if (existing != null) {
                return new int[]{existing, rebindPending.remove(existing) ? 1 : 0};
            }
            if (clientToConId.size() >= maxConnections) {
                // Access-ordered, so the first key is the least recently used.
                Iterator<InetSocketAddress> it = clientToConId.keySet().iterator();
                InetSocketAddress oldest = it.next();
                Integer freed = clientToConId.remove(oldest);
                conIdToClient.remove(freed);
            }
            int conId = nextConId;
            int attempts = 0;
            while (conIdToClient.containsKey(conId)) {
                conId = (conId + 1) & 0xffff;
                if (++attempts > 0xffff) throw new IllegalStateException("no free connection id");
            }
            nextConId = (conId + 1) & 0xffff;
            clientToConId.put(client, conId);
            conIdToClient.put(conId, client);
            rebindPending.remove(conId);
            // A reused id still names a socket the server holds for someone else, so ask it to
            // rebind rather than leaking the previous endpoint's traffic into this one.
            return new int[]{conId, 1};
        }
    }

    private InetSocketAddress clientOf(int conId) {
        synchronized (stateLock) {
            return conIdToClient.get(conId);
        }
    }

    /** The server keeps no state across reconnects, so every live id has to be rebound. */
    private void invalidateConnections() {
        synchronized (stateLock) {
            rebindPending.addAll(conIdToClient.keySet());
        }
    }

    // -------------------------------------------------------------------- tunnel

    private void tunnelLoop() {
        long backoff = RECONNECT_DELAY_MS;
        while (!closed) {
            Socket socket = null;
            try {
                socket = dialThroughUpstream(udpgwHost, udpgwPort);
                tunnelSocket.set(socket);
                log("udpgw tunnel established");
                backoff = RECONNECT_DELAY_MS;
                invalidateConnections();

                Socket current = socket;
                Thread writer = daemon("udpgw-write", () -> writeLoop(current));
                Thread keepalive = daemon("udpgw-keepalive", this::keepaliveLoop);
                try {
                    readLoop(socket.getInputStream());
                } finally {
                    writer.interrupt();
                    keepalive.interrupt();
                }
            } catch (IOException e) {
                if (!closed) {
                    log("udpgw tunnel to " + udpgwHost + ":" + udpgwPort + " failed: "
                            + e.getMessage());
                }
            } finally {
                tunnelSocket.set(null);
                if (socket != null) closeQuietly(socket);
            }
            if (closed) return;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(backoff * 2, RECONNECT_DELAY_MAX_MS);
        }
    }

    private void writeLoop(Socket socket) {
        try {
            OutputStream out = socket.getOutputStream();
            while (!closed && !Thread.currentThread().isInterrupted()) {
                byte[] payload = outgoing.take();
                out.write(payload);
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            closeQuietly(socket);
        }
    }

    private void keepaliveLoop() {
        byte[] keepalive = frame(new byte[]{(byte) FLAG_KEEPALIVE, 0, 0});
        while (!closed && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(KEEPALIVE_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            outgoing.offer(keepalive);
        }
    }

    private void readLoop(InputStream rawInput) throws IOException {
        DataInputStream in = new DataInputStream(rawInput);
        byte[] header = new byte[2];
        while (!closed) {
            try {
                in.readFully(header);
            } catch (EOFException e) {
                return;
            }
            int length = (header[0] & 0xff) | ((header[1] & 0xff) << 8);
            if (length < 3 || length > MAX_PACKET_SIZE) {
                throw new SocketException("bad udpgw frame length " + length);
            }
            byte[] body = new byte[length];
            in.readFully(body);
            deliverToClient(body);
        }
    }

    private void deliverToClient(byte[] body) {
        int flags = body[0] & 0xff;
        if ((flags & FLAG_KEEPALIVE) != 0) return;
        int conId = (body[1] & 0xff) | ((body[2] & 0xff) << 8);
        InetSocketAddress client = clientOf(conId);
        if (client == null) return;

        int addressLength = (flags & FLAG_IPV6) != 0 ? 16 : 4;
        if (body.length < 3 + addressLength + 2) return;
        InetAddress source;
        try {
            source = InetAddress.getByAddress(copy(body, 3, addressLength));
        } catch (IOException e) {
            return;
        }
        int portOffset = 3 + addressLength;
        int port = ((body[portOffset] & 0xff) << 8) | (body[portOffset + 1] & 0xff);
        int payloadOffset = portOffset + 2;

        byte[] reply = new byte[6 + addressLength + (body.length - payloadOffset)];
        reply[3] = (byte) (source instanceof Inet4Address ? 1 : 4);
        System.arraycopy(source.getAddress(), 0, reply, 4, addressLength);
        reply[4 + addressLength] = (byte) (port >>> 8);
        reply[5 + addressLength] = (byte) port;
        System.arraycopy(body, payloadOffset, reply, 6 + addressLength,
                body.length - payloadOffset);

        DatagramSocket udp = udpSocket;
        if (udp == null) return;
        try {
            udp.send(new DatagramPacket(reply, reply.length, client.getAddress(), client.getPort()));
        } catch (IOException e) {
            // the client went away
        }
    }

    private static byte[] frame(byte[] body) {
        byte[] out = new byte[body.length + 2];
        out[0] = (byte) body.length;
        out[1] = (byte) (body.length >>> 8);
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    // --------------------------------------------------------------------- misc

    private static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void closeQuietly(java.io.Closeable target) {
        try {
            target.close();
        } catch (IOException ignored) {
        }
    }

    private static String format(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }

    private static void log(String message) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        System.out.println(time + " udpgw: " + message);
    }

}
