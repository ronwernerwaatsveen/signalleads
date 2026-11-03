package com.signalcrm.signalleads.web;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class UrlGuardTest {

    @Test
    void avviserInterneAdresser() {
        assertFalse(UrlGuard.isAllowed(URI.create("http://127.0.0.1/")));
        assertFalse(UrlGuard.isAllowed(URI.create("http://localhost/")));
        assertFalse(UrlGuard.isAllowed(URI.create("http://169.254.169.254/latest/meta-data")));
        assertFalse(UrlGuard.isAllowed(URI.create("http://10.0.0.5/")));
        assertFalse(UrlGuard.isAllowed(URI.create("http://192.168.1.1/")));
        assertFalse(UrlGuard.isAllowed(URI.create("http://[::1]/")));
        assertFalse(UrlGuard.isAllowed(URI.create("http://[::ffff:127.0.0.1]/")));
        assertFalse(UrlGuard.isAllowed(URI.create("ftp://example.com/")));
        assertFalse(UrlGuard.isAllowed(URI.create("http://example.com:8080/")));
        assertFalse(UrlGuard.isAllowed(URI.create("http://user:pw@example.com/")));
    }

    @Test
    void offentligAdresseErLov() throws Exception {
        assertTrue(UrlGuard.isPublic(InetAddress.getByName("8.8.8.8")));
        assertFalse(UrlGuard.isPublic(InetAddress.getByName("100.64.1.1")));
    }

    @Test
    void robots() {
        Robots r = Robots.parse("User-agent: googlebot\nDisallow: /\n\nUser-agent: *\nDisallow: /admin\nAllow: /admin/public\n");
        assertTrue(r.allowed("/om-oss"));
        assertFalse(r.allowed("/admin/x"));
        assertTrue(r.allowed("/admin/public/y"));
        assertTrue(Robots.parse("").allowed("/"));
    }

    @Test
    void normaliserNettsted() {
        assertEquals("https://www.firma.no/", WebsiteFetcher.normalizeSite("www.Firma.no/om").toString());
        assertEquals("http://firma.no/", WebsiteFetcher.normalizeSite("http://firma.no").toString());
        assertNull(WebsiteFetcher.normalizeSite(" "));
    }
}
