package com.signalcrm.signalleads.web;

import com.signalcrm.signalleads.config.TtlCache;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Henter forsiden og noen få relevante undersider fra en bedrifts nettsted, som tekst.
 *
 * Høflig og avgrenset: respekterer robots.txt (User-agent *), følger maks 3 omdirigeringer
 * (hver sjekket mot {@link UrlGuard}), leser maks app.web.max-page-bytes per side og bare
 * HTML. Undersider velges ut fra lenketekst/sti mot nøkkelord - generelle (om oss, kontakt,
 * karriere) pluss de SignalCRM sender i profilen (f.eks. "iso", "kvalitet", "hms").
 * Resultatet caches i 24 timer (offentlig innhold, likt for alle).
 */
@Component
public class WebsiteFetcher {

    private static final Logger log = LoggerFactory.getLogger(WebsiteFetcher.class);
    private static final int MAX_TEXT_PER_PAGE = 6000;
    private static final int MAX_REDIRECTS = 3;
    /** Sider nesten alle bedrifter har, og som sier noe om virksomheten - ikke kundespesifikt. */
    private static final List<String> GENERAL_KEYWORDS = List.of(
            "om-oss", "om oss", "about", "hvem-vi-er", "virksomhet", "tjenester", "services",
            "karriere", "career", "jobb", "ledige-stillinger", "stillinger");

    private final HttpClient client;
    private final Duration readTimeout;
    private final int maxBytes;
    private final String userAgent;
    private final TtlCache<String, List<WebPage>> cache = new TtlCache<>(Duration.ofHours(24), 5_000);

    public WebsiteFetcher(@Value("${app.web.connect-timeout-ms:5000}") int connectTimeoutMs,
                          @Value("${app.web.read-timeout-ms:10000}") int readTimeoutMs,
                          @Value("${app.web.max-page-bytes:400000}") int maxBytes,
                          @Value("${app.web.user-agent:SignalCRM-LeadResearch/0.1}") String userAgent) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
        this.maxBytes = maxBytes;
        this.userAgent = userAgent;
    }

    /** Normaliserer "firma.no" / "www.firma.no/" til https://www.firma.no - null hvis ugyldig. */
    public static URI normalizeSite(String website) {
        if (website == null || website.isBlank()) return null;
        String w = website.trim();
        if (!w.matches("(?i)^https?://.*")) w = "https://" + w;
        try {
            URI u = URI.create(w);
            if (u.getHost() == null) return null;
            return URI.create(u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getHost().toLowerCase(Locale.ROOT) + "/");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Henter opptil {@code maxPages} sider. Tom liste hvis nettstedet ikke kan eller får hentes.
     * Cache-nøkkelen tar med nøkkelordene, så ulike profiler kan få ulike undersider.
     */
    public List<WebPage> fetchSite(String website, List<String> extraKeywords, int maxPages) {
        URI root = normalizeSite(website);
        if (root == null || maxPages <= 0) return List.of();
        List<String> keywords = new ArrayList<>(GENERAL_KEYWORDS);
        if (extraKeywords != null) {
            for (String k : extraKeywords) if (k != null && !k.isBlank()) keywords.add(k.trim().toLowerCase(Locale.ROOT));
        }
        String key = root + "|" + maxPages + "|" + String.join(",", keywords);
        return cache.get(key, () -> Optional.of(crawl(root, keywords, maxPages))).orElse(List.of());
    }

    private List<WebPage> crawl(URI root, List<String> keywords, int maxPages) {
        List<WebPage> pages = new ArrayList<>();
        Robots robots = Robots.parse(fetchText(root.resolve("/robots.txt"), "text/plain").map(Fetched::body).orElse(""));
        if (!robots.allowed("/")) {
            log.info("robots.txt tillater ikke henting av {}", root);
            return pages;
        }
        Optional<Fetched> home = fetchText(root, "text/html");
        if (home.isEmpty()) return pages;
        Document doc = Jsoup.parse(home.get().body(), home.get().finalUri().toString());
        pages.add(toPage(home.get().finalUri().toString(), doc));

        String siteHost = stripWww(home.get().finalUri().getHost());
        Map<String, Integer> candidates = new LinkedHashMap<>();
        for (Element a : doc.select("a[href]")) {
            String abs = a.absUrl("href");
            if (abs.isBlank()) continue;
            URI u;
            try { u = URI.create(abs.split("#")[0]); } catch (Exception e) { continue; }
            if (u.getHost() == null || !stripWww(u.getHost().toLowerCase(Locale.ROOT)).equals(siteHost)) continue;
            String path = u.getPath() == null ? "" : u.getPath().toLowerCase(Locale.ROOT);
            if (path.isBlank() || path.equals("/") || path.matches(".*\\.(pdf|jpe?g|png|gif|svg|zip|docx?|xlsx?)$")) continue;
            if (!robots.allowed(path)) continue;
            String haystack = (path + " " + a.text()).toLowerCase(Locale.ROOT);
            int hits = 0;
            for (String k : keywords) if (haystack.contains(k)) hits++;
            if (hits > 0) candidates.merge(u.toString(), hits, Math::max);
        }
        candidates.entrySet().stream()
                .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
                .limit(maxPages - 1L)
                .forEach(e -> fetchText(URI.create(e.getKey()), "text/html").ifPresent(f ->
                        pages.add(toPage(f.finalUri().toString(), Jsoup.parse(f.body(), f.finalUri().toString())))));
        return pages;
    }

    private WebPage toPage(String url, Document doc) {
        doc.select("script, style, noscript, svg, nav, footer, form, iframe").remove();
        String text = doc.body() != null ? doc.body().text() : "";
        if (text.length() > MAX_TEXT_PER_PAGE) text = text.substring(0, MAX_TEXT_PER_PAGE);
        return new WebPage(url, doc.title(), text);
    }

    private record Fetched(URI finalUri, String body) {}

    /** GET med manuell omdirigering, adressesjekk per steg, størrelsestak og innholdstypesjekk. */
    private Optional<Fetched> fetchText(URI uri, String expectedType) {
        URI current = uri;
        try {
            for (int i = 0; i <= MAX_REDIRECTS; i++) {
                if (!UrlGuard.isAllowed(current)) return Optional.empty();
                HttpRequest req = HttpRequest.newBuilder(current).timeout(readTimeout)
                        .header("User-Agent", userAgent)
                        .header("Accept", expectedType + ",*/*;q=0.5")
                        .GET().build();
                HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int status = res.statusCode();
                if (status >= 300 && status < 400) {
                    res.body().close();
                    Optional<String> loc = res.headers().firstValue("location");
                    if (loc.isEmpty()) return Optional.empty();
                    current = current.resolve(loc.get());
                    continue;
                }
                if (status != 200) { res.body().close(); return Optional.empty(); }
                String type = res.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
                if (expectedType.equals("text/html") && !type.contains("html")) { res.body().close(); return Optional.empty(); }
                byte[] bytes = readLimited(res.body());
                return Optional.of(new Fetched(current, new String(bytes, charset(type))));
            }
        } catch (Exception e) {
            log.debug("Klarte ikke hente {}: {}", current, e.getMessage());
        }
        return Optional.empty();
    }

    private byte[] readLimited(InputStream in) throws Exception {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                int room = maxBytes - out.size();
                if (room <= 0) break;
                out.write(buf, 0, Math.min(n, room));
            }
            return out.toByteArray();
        }
    }

    private static Charset charset(String contentType) {
        int i = contentType.indexOf("charset=");
        if (i >= 0) {
            try { return Charset.forName(contentType.substring(i + 8).replace("\"", "").split(";")[0].trim()); }
            catch (Exception ignored) { /* ukjent tegnsett - bruk UTF-8 */ }
        }
        return StandardCharsets.UTF_8;
    }

    private static String stripWww(String host) {
        return host.startsWith("www.") ? host.substring(4) : host;
    }
}
