package de.palsoftware.yvoke.shared.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Validates resolved IP addresses against SSRF target ranges (loopback, link-local / cloud
 * metadata, RFC 1918 private, CGNAT, IPv6 ULA, multicast, unspecified, broadcast, reserved, and
 * IPv4-mapped IPv6).
 *
 * <p>
 * Used both upfront during URL validation and at connect time inside Apache HttpClient's
 * {@code DnsResolver} to defeat DNS rebinding.
 */
public final class SsrfAddressValidator {

    private SsrfAddressValidator() {}

    public static InetAddress[] resolveAndValidate(String host) throws UnknownHostException {
        if (host == null || host.isBlank()) {
            throw new UnknownHostException("Host must not be blank");
        }
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new UnknownHostException("No addresses resolved for host: " + host);
        }
        for (InetAddress addr : addresses) {
            if (isBlockedAddress(addr)) {
                throw new UnknownHostException(
                    "Host resolves to a blocked address (" + addr.getHostAddress() + "): " + host);
            }
        }
        return addresses;
    }

    public static boolean isBlockedAddress(InetAddress addr) {
        if (addr == null) {
            return true;
        }
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
            || addr.isMulticastAddress() || addr.isSiteLocalAddress()) {
            return true;
        }
        byte[] raw = addr.getAddress();
        if (addr instanceof Inet4Address || raw.length == 4) {
            return isBlockedIpv4(raw);
        }
        if (addr instanceof Inet6Address && raw.length == 16) {
            // IPv6 Unique Local Address: fc00::/7
            int firstByte = raw[0] & 0xFF;
            if ((firstByte & 0xFE) == 0xFC) {
                return true;
            }
            // IPv4-mapped IPv6 (::ffff:a.b.c.d) — unwrap and apply IPv4 checks
            if (isIpv4MappedIpv6(raw)) {
                byte[] v4 = new byte[] {raw[12], raw[13], raw[14], raw[15]};
                if (isBlockedIpv4(v4)) {
                    return true;
                }
                try {
                    InetAddress mapped = InetAddress.getByAddress(v4);
                    return mapped.isLoopbackAddress() || mapped.isAnyLocalAddress()
                        || mapped.isLinkLocalAddress() || mapped.isMulticastAddress()
                        || mapped.isSiteLocalAddress();
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBlockedIpv4(byte[] b) {
        int b0 = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;
        // 0.0.0.0/8 ("this network")
        if (b0 == 0) {
            return true;
        }
        // 10.0.0.0/8
        if (b0 == 10) {
            return true;
        }
        // 100.64.0.0/10 (RFC 6598 CGNAT / Alibaba/cloud metadata)
        if (b0 == 100 && (b1 & 0xC0) == 64) {
            return true;
        }
        // 127.0.0.0/8 (loopback)
        if (b0 == 127) {
            return true;
        }
        // 169.254.0.0/16 (link-local / AWS/GCP/Azure IMDS)
        if (b0 == 169 && b1 == 254) {
            return true;
        }
        // 172.16.0.0/12
        if (b0 == 172 && (b1 & 0xF0) == 16) {
            return true;
        }
        // 192.168.0.0/16
        if (b0 == 192 && b1 == 168) {
            return true;
        }
        // 198.18.0.0/15 (benchmarking)
        if (b0 == 198 && (b1 == 18 || b1 == 19)) {
            return true;
        }
        // 224.0.0.0/4 (multicast) and 240.0.0.0/4 (reserved / broadcast 255.255.255.255)
        if (b0 >= 224) {
            return true;
        }
        return false;
    }

    private static boolean isIpv4MappedIpv6(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
    }
}
