package com.signalcrm.signalleads.web;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * Vern mot SSRF: nettstedsadressene kommer fra Brønnøysund og fra brukere i SignalCRM, og tjenesten
 * kjører på samme vert som SignalCRM og databasen. Bare http/https på standardporter, og bare
 * offentlige adresser - aldri localhost, private nett, link-local (bl.a. skyens metadata-tjeneste
 * 169.254.169.254) eller multicast. Sjekkes på nytt for hver omdirigering.
 */
public final class UrlGuard {

    private UrlGuard() {}

    public static boolean isAllowed(URI uri) {
        if (uri == null || uri.getHost() == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return false;
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) return false;
        if (uri.getUserInfo() != null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal")) return false;
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) return false;
            for (InetAddress a : addresses) {
                if (!isPublic(a)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 0 || b0 == 127) return false;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return false; // CGNAT 100.64.0.0/10
            if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) return false;
            if (b0 >= 224) return false;
        } else if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return false; // ULA fc00::/7
            // IPv4-mappet (::ffff:a.b.c.d) - vurder selve IPv4-adressen
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) { mapped = false; break; }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (Exception e) {
                    return false;
                }
            }
        }
        return true;
    }
}
