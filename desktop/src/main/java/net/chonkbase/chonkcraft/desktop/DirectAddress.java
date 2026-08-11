package net.chonkbase.chonkcraft.desktop;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.DatagramSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A host and UDP port entered or advertised for a direct multiplayer game. */
record DirectAddress(String host, int port) {

    static final int MIN_USER_PORT = 1024;
    static final int MAX_PORT = 65_535;

    DirectAddress {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Enter the host's IP address or name.");
        }
        validatePort(port);
        host = stripBrackets(host.trim());
    }

    /** Parses names, IPv4, raw IPv6, and bracketed IPv6 with an optional port. */
    static DirectAddress parse(String text, int defaultPort) {
        validatePort(defaultPort);
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Enter the host's IP address or name.");
        }
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("That IPv6 address needs a closing bracket.");
            }
            String host = value.substring(1, close);
            String suffix = value.substring(close + 1);
            if (suffix.isEmpty()) {
                return new DirectAddress(host, defaultPort);
            }
            if (!suffix.startsWith(":")) {
                throw new IllegalArgumentException("Put the UDP port after a colon.");
            }
            return new DirectAddress(host, parsePort(suffix.substring(1)));
        }

        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon >= 0 && firstColon == lastColon) {
            return new DirectAddress(value.substring(0, firstColon),
                    parsePort(value.substring(firstColon + 1)));
        }
        // More than one colon is a raw IPv6 literal. A non-default port on IPv6
        // is deliberately bracketed so its boundary is never guessed.
        return new DirectAddress(value, defaultPort);
    }

    static int parsePort(String text) {
        try {
            int parsed = Integer.parseInt(text == null ? "" : text.trim());
            validatePort(parsed);
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("The UDP port must be a number from 1024 to 65535.");
        }
    }

    static void validatePort(int port) {
        if (port < MIN_USER_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("The UDP port must be from 1024 to 65535.");
        }
    }

    String display() {
        return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
    }

    /** Reachable private addresses to use as a router's forwarding destination. */
    static List<String> localEndpoints(int port) {
        validatePort(port);
        Set<String> found = new LinkedHashSet<>();
        String preferred = preferredIpv4();
        if (preferred != null) {
            found.add(new DirectAddress(preferred, port).display());
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface adapter = interfaces.nextElement();
                    if (!adapter.isUp() || adapter.isLoopback() || adapter.isVirtual()) {
                        continue;
                    }
                    Enumeration<InetAddress> addresses = adapter.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (address instanceof Inet4Address && !address.isLoopbackAddress()
                                && !address.isLinkLocalAddress()) {
                            found.add(new DirectAddress(address.getHostAddress(), port).display());
                        }
                    }
                }
            }
        } catch (SocketException unavailable) {
            // The route-selected address above is still useful when adapter
            // enumeration is restricted by the operating system.
        }
        List<String> sorted = new ArrayList<>(found);
        String preferredEndpoint = preferred == null ? null
                : new DirectAddress(preferred, port).display();
        sorted.sort(Comparator.comparing((String endpoint) -> !endpoint.equals(preferredEndpoint))
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(sorted);
    }

    /** The address the operating system would route ordinary internet traffic through. */
    private static String preferredIpv4() {
        try (DatagramSocket route = new DatagramSocket()) {
            // UDP connect selects a route but sends no packet. The documentation
            // address is not a dependency and cannot receive application traffic.
            route.connect(InetAddress.getByName("192.0.2.1"), 9);
            InetAddress local = route.getLocalAddress();
            return local instanceof Inet4Address && !local.isAnyLocalAddress()
                    ? local.getHostAddress() : null;
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
    }
}
