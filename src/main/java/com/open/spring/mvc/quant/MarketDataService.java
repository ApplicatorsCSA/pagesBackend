package com.open.spring.mvc.quant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import org.springframework.web.util.UriComponentsBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pulls historical daily OHLCV from Alpha Vantage.
 *
 * Uses:
 * - TIME_SERIES_DAILY (free tier daily OHLCV)
 *
 * Config:
 * - env var `ALPHAVANTAGE_API_KEY` or Spring property `alphavantage.apiKey`
 */
@Service
public class MarketDataService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Request-level caches (mirrors the "in-memory cache + Streamlit cache" idea).
     * Keyed by: SYMBOL|START|END
     */
    private static final long MEM_TTL_MS = 10 * 60 * 1000L; // 10 minutes
    private static final long STREAMLIT_TTL_MS = 60 * 60 * 1000L; // 1 hour
    private final Map<String, RangeCacheEntry> memRangeCache = new ConcurrentHashMap<>();
    private final Map<String, RangeCacheEntry> streamlitRangeCache = new ConcurrentHashMap<>();

    private static class RangeCacheEntry {
        final long expiresAtMs;
        final List<Bar> bars;
        RangeCacheEntry(long expiresAtMs, List<Bar> bars) {
            this.expiresAtMs = expiresAtMs;
            this.bars = bars;
        }
    }

    /**
     * Alpha Vantage free tier is rate-limited. Cache the most recent successful series per symbol
     * so multiple endpoints (history/indicators/ml/backtest) don't re-hit AV repeatedly.
     */
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L; // 6 hours
    private final Map<String, CacheEntry> seriesCache = new ConcurrentHashMap<>();

    private static class CacheEntry {
        final long fetchedAtMs;
        final List<Bar> bars; // sorted ascending
        CacheEntry(long fetchedAtMs, List<Bar> bars) {
            this.fetchedAtMs = fetchedAtMs;
            this.bars = bars;
        }
    }

    /**
     * Alpha Vantage API key.
     * Provide via env var `ALPHAVANTAGE_API_KEY` or Spring property `alphavantage.apiKey`.
     */
    @Value("${alphavantage.apiKey:${ALPHAVANTAGE_API_KEY:}}")
    private String alphaVantageApiKey;

    /**
     * Market data provider selection.
     * - auto (default): try Alpha Vantage if configured; fall back to Yahoo Finance on throttles/errors
     * - yahoo: always use Yahoo Finance (no API key, unofficial endpoint)
     * - alphavantage: always use Alpha Vantage (requires key, rate-limited)
     */
    @Value("${market.provider:auto}")
    private String marketProvider;

    @Value("${market.local.enabled:true}")
    private boolean localFallbackEnabled;

    public List<Bar> getDailyBars(String ticker, LocalDate start, LocalDate end) {
        String sym = (ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT));
        if (sym.isBlank()) throw new IllegalArgumentException("ticker is required");
        if (start == null || end == null) throw new IllegalArgumentException("start/end are required");
        if (end.isBefore(start)) throw new IllegalArgumentException("end must be >= start");

        // 1) in-memory cache (range-specific)
        String rangeKey = sym + "|" + start + "|" + end;
        List<Bar> memHit = getIfFresh(memRangeCache, rangeKey);
        if (memHit != null) return memHit;

        // 2) Streamlit-like cache (range-specific, 1h TTL)
        List<Bar> stHit = getIfFresh(streamlitRangeCache, rangeKey);
        if (stHit != null) {
            put(memRangeCache, rangeKey, stHit, MEM_TTL_MS);
            return stHit;
        }

        String key = alphaVantageApiKey == null ? "" : alphaVantageApiKey.trim();
        String provider = (marketProvider == null ? "auto" : marketProvider.trim().toLowerCase(Locale.ROOT));

        // Try cache first (fresh within TTL)
        CacheEntry cached = seriesCache.get(sym);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.fetchedAtMs) < CACHE_TTL_MS) {
            return filterRange(cached.bars, start, end);
        }

        // Provider choice
        if ("yahoo".equals(provider)) {
            try {
                List<Bar> yahoo = fetchYahooLayered(sym, start, end);
                if (!yahoo.isEmpty()) {
                    seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), yahoo));
                }
                List<Bar> out = filterRange(yahoo, start, end);
                put(memRangeCache, rangeKey, out, MEM_TTL_MS);
                put(streamlitRangeCache, rangeKey, out, STREAMLIT_TTL_MS);
                return out;
            } catch (Exception e) {
                if (localFallbackEnabled) {
                    List<Bar> local = fetchFromLocalCsv(sym);
                    if (!local.isEmpty()) {
                        seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), local));
                        List<Bar> out = filterRange(local, start, end);
                        put(memRangeCache, rangeKey, out, MEM_TTL_MS);
                        put(streamlitRangeCache, rangeKey, out, STREAMLIT_TTL_MS);
                        return out;
                    }
                }
                throw e;
            }
        }

        if ("alphavantage".equals(provider) || "auto".equals(provider)) {
            if (key.isBlank()) {
                if ("alphavantage".equals(provider)) {
                    throw new IllegalStateException(
                            "Missing Alpha Vantage API key. Set env ALPHAVANTAGE_API_KEY (or property alphavantage.apiKey) on the Spring server."
                    );
                }
                // auto mode with no key -> use Yahoo
                try {
                    List<Bar> yahoo = fetchYahooLayered(sym, start, end);
                    if (!yahoo.isEmpty()) {
                        seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), yahoo));
                    }
                    List<Bar> out = filterRange(yahoo, start, end);
                    put(memRangeCache, rangeKey, out, MEM_TTL_MS);
                    put(streamlitRangeCache, rangeKey, out, STREAMLIT_TTL_MS);
                    return out;
                } catch (Exception e) {
                    if (localFallbackEnabled) {
                        List<Bar> local = fetchFromLocalCsv(sym);
                        if (!local.isEmpty()) {
                            seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), local));
                            List<Bar> out = filterRange(local, start, end);
                            put(memRangeCache, rangeKey, out, MEM_TTL_MS);
                            put(streamlitRangeCache, rangeKey, out, STREAMLIT_TTL_MS);
                            return out;
                        }
                    }
                    throw e;
                }
            }
        }

        // Alpha Vantage returns latest first; we'll sort ascending at the end.
        String url = UriComponentsBuilder
                .fromHttpUrl("https://www.alphavantage.co/query")
                .queryParam("function", "TIME_SERIES_DAILY")
                .queryParam("symbol", sym)
                // Free tier: omit outputsize=full (premium). Defaults to compact (~100 most recent points).
                .queryParam("apikey", key)
                .toUriString();

        String json = restTemplate.getForObject(url, String.class);
        if (json == null || json.isBlank()) return List.of();

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Alpha Vantage response", e);
        }

        // Error / throttle responses
        if (root.hasNonNull("Error Message")) {
            if ("auto".equals(provider)) {
                try {
                    List<Bar> yahoo = fetchYahooLayered(sym, start, end);
                    if (!yahoo.isEmpty()) {
                        seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), yahoo));
                        List<Bar> out = filterRange(yahoo, start, end);
                        put(memRangeCache, rangeKey, out, MEM_TTL_MS);
                        put(streamlitRangeCache, rangeKey, out, STREAMLIT_TTL_MS);
                        return out;
                    }
                } catch (Exception ignored) {
                    // fall through to local fallback below
                }
                if (localFallbackEnabled) {
                    List<Bar> local = fetchFromLocalCsv(sym);
                    if (!local.isEmpty()) {
                        seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), local));
                        List<Bar> out = filterRange(local, start, end);
                        put(memRangeCache, rangeKey, out, MEM_TTL_MS);
                        put(streamlitRangeCache, rangeKey, out, STREAMLIT_TTL_MS);
                        return out;
                    }
                }
            }
            throw new IllegalStateException("Alpha Vantage error: " + root.get("Error Message").asText());
        }
        if (root.hasNonNull("Note")) {
            // Rate-limited: fall back to last cached data if available
            CacheEntry fallback = seriesCache.get(sym);
            if (fallback != null && fallback.bars != null && !fallback.bars.isEmpty()) {
                return filterRange(fallback.bars, start, end);
            }
            if ("auto".equals(provider)) {
                try {
                    List<Bar> yahoo = fetchYahooLayered(sym, start, end);
                    if (!yahoo.isEmpty()) {
                        seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), yahoo));
                        List<Bar> out = filterRange(yahoo, start, end);
                        put(memRangeCache, rangeKey, out, MEM_TTL_MS);
                        put(streamlitRangeCache, rangeKey, out, STREAMLIT_TTL_MS);
                        return out;
                    }
                } catch (Exception ignored) {}
                if (localFallbackEnabled) {
                    List<Bar> local = fetchFromLocalCsv(sym);
                    if (!local.isEmpty()) {
                        seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), local));
                        List<Bar> out = filterRange(local, start, end);
                        put(memRangeCache, rangeKey, out, MEM_TTL_MS);
                        put(streamlitRangeCache, rangeKey, out, STREAMLIT_TTL_MS);
                        return out;
                    }
                }
            }
            throw new IllegalStateException("Alpha Vantage throttle: " + root.get("Note").asText());
        }
        if (root.hasNonNull("Information")) {
            // Some "Information" responses are rate-limit messaging on certain keys.
            CacheEntry fallback = seriesCache.get(sym);
            if (fallback != null && fallback.bars != null && !fallback.bars.isEmpty()) {
                return filterRange(fallback.bars, start, end);
            }
            if ("auto".equals(provider)) {
                try {
                    List<Bar> yahoo = fetchYahooLayered(sym, start, end);
                    if (!yahoo.isEmpty()) {
                        seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), yahoo));
                        List<Bar> out = filterRange(yahoo, start, end);
                        put(memRangeCache, rangeKey, out, MEM_TTL_MS);
                        put(streamlitRangeCache, rangeKey, out, STREAMLIT_TTL_MS);
                        return out;
                    }
                } catch (Exception ignored) {}
                if (localFallbackEnabled) {
                    List<Bar> local = fetchFromLocalCsv(sym);
                    if (!local.isEmpty()) {
                        seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), local));
                        List<Bar> out = filterRange(local, start, end);
                        put(memRangeCache, rangeKey, out, MEM_TTL_MS);
                        put(streamlitRangeCache, rangeKey, out, STREAMLIT_TTL_MS);
                        return out;
                    }
                }
            }
            throw new IllegalStateException("Alpha Vantage info: " + root.get("Information").asText());
        }

        JsonNode series = root.get("Time Series (Daily)");
        if (series == null || !series.isObject()) {
            return List.of();
        }

        List<Bar> out = new ArrayList<>();
        series.fields().forEachRemaining(entry -> {
            try {
                LocalDate d = LocalDate.parse(entry.getKey());
                JsonNode row = entry.getValue();
                double open = row.path("1. open").asDouble(Double.NaN);
                double high = row.path("2. high").asDouble(Double.NaN);
                double low = row.path("3. low").asDouble(Double.NaN);
                double close = row.path("4. close").asDouble(Double.NaN);
                long volume = row.path("5. volume").asLong(0);

                if (!Double.isFinite(close) || close <= 0) return;
                Instant t = d.atStartOfDay().toInstant(ZoneOffset.UTC);
                out.add(new Bar(sym, t, open, high, low, close, volume, "1d"));
            } catch (Exception ignored) {
                // skip malformed rows
            }
        });

        out.sort(Comparator.comparing(Bar::getTime));
        // Cache full compact series; then filter for the requested range
        if (!out.isEmpty()) {
            seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), out));
        }
        List<Bar> finalOut = filterRange(out, start, end);
        put(memRangeCache, rangeKey, finalOut, MEM_TTL_MS);
        put(streamlitRangeCache, rangeKey, finalOut, STREAMLIT_TTL_MS);
        return finalOut;
    }

    private List<Bar> fetchYahooLayered(String symbol, LocalDate start, LocalDate end) {
        // 3) exact epoch range
        try {
            List<Bar> bars = fetchYahooByEpochRange(symbol, start, end);
            if (!bars.isEmpty()) return bars;
        } catch (Exception ignored) {}

        // 4) period/range then trim
        try {
            String range = toYahooRange(start, end);
            List<Bar> bars = fetchYahooByRange(symbol, range);
            List<Bar> trimmed = filterRange(bars, start, end);
            if (!trimmed.isEmpty()) return trimmed;
        } catch (Exception ignored) {}

        // 5) fallback 1y then trim
        try {
            List<Bar> bars = fetchYahooByRange(symbol, "1y");
            List<Bar> trimmed = filterRange(bars, start, end);
            if (!trimmed.isEmpty()) return trimmed;
        } catch (Exception ignored) {}

        // 6) alternate download CSV endpoint then trim
        try {
            List<Bar> bars = fetchYahooDownloadCsv(symbol, start, end);
            List<Bar> trimmed = filterRange(bars, start, end);
            if (!trimmed.isEmpty()) return trimmed;
        } catch (Exception ignored) {}

        // then optional local CSV
        if (localFallbackEnabled) {
            List<Bar> local = fetchFromLocalCsv(symbol);
            if (!local.isEmpty()) return local;
        }

        throw new IllegalStateException("Yahoo Finance fetch failed (all fallback methods exhausted)");
    }

    private String toYahooRange(LocalDate start, LocalDate end) {
        long days = Math.max(1, ChronoUnit.DAYS.between(start, end) + 1);
        if (days <= 31) return "3mo";
        if (days <= 93) return "6mo";
        if (days <= 186) return "1y";
        if (days <= 365) return "2y";
        return "5y";
    }

    private List<Bar> fetchYahooByEpochRange(String symbol, LocalDate start, LocalDate end) throws Exception {
        long period1 = start.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        long period2 = end.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        String[] hosts = new String[] {"https://query2.finance.yahoo.com", "https://query1.finance.yahoo.com"};
        for (String host : hosts) {
            String url = UriComponentsBuilder
                    .fromHttpUrl(host + "/v8/finance/chart/" + symbol)
                    .queryParam("interval", "1d")
                    .queryParam("period1", period1)
                    .queryParam("period2", period2)
                    .toUriString();
            List<Bar> out = yahooRequestAndParseChart(url, symbol);
            if (!out.isEmpty()) return out;
        }
        return List.of();
    }

    private List<Bar> fetchYahooByRange(String symbol, String range) throws Exception {
        String[] hosts = new String[] {"https://query2.finance.yahoo.com", "https://query1.finance.yahoo.com"};
        for (String host : hosts) {
            String url = UriComponentsBuilder
                    .fromHttpUrl(host + "/v8/finance/chart/" + symbol)
                    .queryParam("interval", "1d")
                    .queryParam("range", range)
                    .toUriString();
            List<Bar> out = yahooRequestAndParseChart(url, symbol);
            if (!out.isEmpty()) return out;
        }
        return List.of();
    }

    private List<Bar> yahooRequestAndParseChart(String url, String symbol) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        String json = resp.getBody();
        if (json == null || json.isBlank()) return List.of();
        JsonNode root = objectMapper.readTree(json);
        return parseYahooChart(root, symbol);
    }

    private List<Bar> fetchYahooDownloadCsv(String symbol, LocalDate start, LocalDate end) throws Exception {
        long period1 = start.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        long period2 = end.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC);

        String url = UriComponentsBuilder
                .fromHttpUrl("https://query1.finance.yahoo.com/v7/finance/download/" + urlEncode(symbol))
                .queryParam("period1", period1)
                .queryParam("period2", period2)
                .queryParam("interval", "1d")
                .queryParam("events", "history")
                .queryParam("includeAdjustedClose", "true")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_PLAIN, MediaType.ALL));
        headers.set("User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        String csv = resp.getBody();
        if (csv == null || csv.isBlank()) return List.of();
        return parseYahooDownloadCsv(csv, symbol);
    }

    private String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private List<Bar> parseYahooDownloadCsv(String csv, String symbol) throws Exception {
        List<Bar> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new StringReader(csv))) {
            String header = br.readLine();
            if (header == null) return List.of();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("null")) continue;
                String[] p = line.split(",");
                if (p.length < 7) continue;
                LocalDate d = LocalDate.parse(p[0].trim());
                Instant t = d.atStartOfDay().toInstant(ZoneOffset.UTC);
                double o = parseDouble(p[1]);
                double h = parseDouble(p[2]);
                double l = parseDouble(p[3]);
                double c = parseDouble(p[4]);
                long v = parseLong(p[6]);
                if (!Double.isFinite(c) || c <= 0) continue;
                out.add(new Bar(symbol, t, o, h, l, c, v, "1d"));
            }
        }
        out.sort(Comparator.comparing(Bar::getTime));
        return out;
    }

    private List<Bar> parseYahooChart(JsonNode root, String symbol) {
        try {
            JsonNode result0 = root.path("chart").path("result");
            if (!result0.isArray() || result0.isEmpty()) return List.of();
            JsonNode r = result0.get(0);

            JsonNode ts = r.path("timestamp");
            JsonNode quote0 = r.path("indicators").path("quote");
            if (!quote0.isArray() || quote0.isEmpty()) return List.of();
            JsonNode q = quote0.get(0);

            JsonNode open = q.path("open");
            JsonNode high = q.path("high");
            JsonNode low = q.path("low");
            JsonNode close = q.path("close");
            JsonNode vol = q.path("volume");

            if (!ts.isArray()) return List.of();

            List<Bar> out = new ArrayList<>();
            for (int i = 0; i < ts.size(); i++) {
                long epochSec = ts.get(i).asLong(0);
                if (epochSec <= 0) continue;

                double o = open.path(i).isNumber() ? open.get(i).asDouble() : Double.NaN;
                double h = high.path(i).isNumber() ? high.get(i).asDouble() : Double.NaN;
                double l = low.path(i).isNumber() ? low.get(i).asDouble() : Double.NaN;
                double c = close.path(i).isNumber() ? close.get(i).asDouble() : Double.NaN;
                long v = vol.path(i).isNumber() ? vol.get(i).asLong() : 0L;

                if (!Double.isFinite(c) || c <= 0) continue;
                Instant t = Instant.ofEpochSecond(epochSec);
                out.add(new Bar(symbol, t, o, h, l, c, v, "1d"));
            }

            out.sort(Comparator.comparing(Bar::getTime));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Yahoo Finance parse failed", e);
        }
    }

    private List<Bar> fetchFromLocalCsv(String symbol) {
        // Looks for: src/main/resources/market-data/<SYMBOL>.csv on the classpath
        String path = "market-data/" + symbol.toUpperCase(Locale.ROOT) + ".csv";
        ClassPathResource res = new ClassPathResource(path);
        if (!res.exists()) return List.of();

        List<Bar> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(res.getInputStream()))) {
            String header = br.readLine(); // Date,Open,High,Low,Close,Volume
            if (header == null) return List.of();

            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length < 6) continue;
                LocalDate d = LocalDate.parse(p[0].trim());
                Instant t = d.atStartOfDay().toInstant(ZoneOffset.UTC);
                double o = parseDouble(p[1]);
                double h = parseDouble(p[2]);
                double l = parseDouble(p[3]);
                double c = parseDouble(p[4]);
                long v = parseLong(p[5]);
                if (!Double.isFinite(c) || c <= 0) continue;
                out.add(new Bar(symbol.toUpperCase(Locale.ROOT), t, o, h, l, c, v, "1d"));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Local CSV parse failed for " + path, e);
        }

        out.sort(Comparator.comparing(Bar::getTime));
        return out;
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return Double.NaN; }
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }

    private List<Bar> getIfFresh(Map<String, RangeCacheEntry> cache, String key) {
        RangeCacheEntry e = cache.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() > e.expiresAtMs) {
            cache.remove(key);
            return null;
        }
        return e.bars;
    }

    private void put(Map<String, RangeCacheEntry> cache, String key, List<Bar> bars, long ttlMs) {
        cache.put(key, new RangeCacheEntry(System.currentTimeMillis() + ttlMs, bars));
    }

    private List<Bar> filterRange(List<Bar> bars, LocalDate start, LocalDate end) {
        if (bars == null || bars.isEmpty()) return List.of();
        List<Bar> filtered = new ArrayList<>();
        for (Bar b : bars) {
            LocalDate d = b.getTime().atZone(ZoneOffset.UTC).toLocalDate();
            if ((d.isEqual(start) || d.isAfter(start)) && (d.isEqual(end) || d.isBefore(end))) {
                filtered.add(b);
            }
        }
        filtered.sort(Comparator.comparing(Bar::getTime));
        return filtered;
    }
}
