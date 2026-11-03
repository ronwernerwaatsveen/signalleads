package com.signalcrm.signalleads.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal robots.txt-tolkning: Disallow/Allow-regler for "User-agent: *" (lengste treff vinner,
 * Allow vinner ved likt). Nok til å være høflig; avanserte jokertegn tolkes ikke.
 */
final class Robots {

    private final List<String> allow = new ArrayList<>();
    private final List<String> disallow = new ArrayList<>();

    static Robots parse(String txt) {
        Robots r = new Robots();
        boolean applies = false;
        boolean lastWasAgent = false;
        for (String raw : txt.split("\\r?\\n")) {
            String line = raw.replaceAll("#.*$", "").trim();
            if (line.isEmpty()) continue;
            int c = line.indexOf(':');
            if (c < 0) continue;
            String field = line.substring(0, c).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(c + 1).trim();
            if (field.equals("user-agent")) {
                boolean star = value.equals("*");
                applies = lastWasAgent ? (applies || star) : star;
                lastWasAgent = true;
                continue;
            }
            lastWasAgent = false;
            if (!applies) continue;
            String rule = value.replace("*", "");
            if (field.equals("disallow") && !rule.isEmpty()) r.disallow.add(rule);
            if (field.equals("allow") && !rule.isEmpty()) r.allow.add(rule);
        }
        return r;
    }

    boolean allowed(String path) {
        int bestDis = longest(disallow, path);
        if (bestDis < 0) return true;
        return longest(allow, path) >= bestDis;
    }

    private static int longest(List<String> rules, String path) {
        int best = -1;
        for (String r : rules) if (path.startsWith(r) && r.length() > best) best = r.length();
        return best;
    }
}
