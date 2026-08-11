package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Direct addresses have one unambiguous cross-platform spelling. */
class DirectAddressTest {

    @Test
    @DisplayName("IPv4 and hostnames use the visible port or the direct default")
    void namesAndIpv4UseTheIntendedPort() {
        assertEquals(new DirectAddress("203.0.113.20", 7100),
                DirectAddress.parse("203.0.113.20", 7100));
        assertEquals(new DirectAddress("game.example.net", 8123),
                DirectAddress.parse("game.example.net:8123", 7100));
        assertEquals("game.example.net:8123",
                DirectAddress.parse("game.example.net:8123", 7100).display());
    }

    @Test
    @DisplayName("IPv6 keeps its colons and brackets only the shared endpoint")
    void ipv6IsNotMistakenForAHostnameAndPort() {
        assertEquals(new DirectAddress("2001:db8::20", 7100),
                DirectAddress.parse("2001:db8::20", 7100));
        DirectAddress explicit = DirectAddress.parse("[2001:db8::20]:8123", 7100);
        assertEquals("2001:db8::20", explicit.host());
        assertEquals(8123, explicit.port());
        assertEquals("[2001:db8::20]:8123", explicit.display());
    }

    @Test
    @DisplayName("Privileged, oversized, missing, and invented ports are refused")
    void invalidPortsNeverReachTheNetwork() {
        assertThrows(IllegalArgumentException.class,
                () -> DirectAddress.parse("host:abc", 7100));
        assertThrows(IllegalArgumentException.class,
                () -> DirectAddress.parse("host:80", 7100));
        assertThrows(IllegalArgumentException.class,
                () -> DirectAddress.parse("host:70000", 7100));
        assertThrows(IllegalArgumentException.class,
                () -> DirectAddress.parse("[2001:db8::20", 7100));
    }
}
