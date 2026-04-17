package com.open.spring.mvc.quant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import org.springframework.web.util.UriComponentsBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

    public List<Bar> getDailyBars(String ticker, LocalDate start, LocalDate end) {
        String sym = (ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT));
        if (sym.isBlank()) throw new IllegalArgumentException("ticker is required");
        if (start == null || end == null) throw new IllegalArgumentException("start/end are required");
        if (end.isBefore(start)) throw new IllegalArgumentException("end must be >= start");

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
            List<Bar> yahoo = fetchFromYahoo(sym);
            if (!yahoo.isEmpty()) {
                seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), yahoo));
            }
            return filterRange(yahoo, start, end);
        }

        if ("alphavantage".equals(provider) || "auto".equals(provider)) {
            if (key.isBlank()) {
                if ("alphavantage".equals(provider)) {
                    throw new IllegalStateException(
                            "Missing Alpha Vantage API key. Set env ALPHAVANTAGE_API_KEY (or property alphavantage.apiKey) on the Spring server."
                    );
                }
                // auto mode with no key -> use Yahoo
                List<Bar> yahoo = fetchFromYahoo(sym);
                if (!yahoo.isEmpty()) {
                    seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), yahoo));
                }
                return filterRange(yahoo, start, end);
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
                List<Bar> yahoo = fetchFromYahoo(sym);
                if (!yahoo.isEmpty()) {
                    seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), yahoo));
                    return filterRange(yahoo, start, end);
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
                List<Bar> yahoo = fetchFromYahoo(sym);
                if (!yahoo.isEmpty()) {
                    seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), yahoo));
                    return filterRange(yahoo, start, end);
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
                List<Bar> yahoo = fetchFromYahoo(sym);
                if (!yahoo.isEmpty()) {
                    seriesCache.put(sym, new CacheEntry(System.currentTimeMillis(), yahoo));
                    return filterRange(yahoo, start, end);
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
        return filterRange(out, start, end);
    }

    private List<Bar> fetchFromYahoo(String symbol) {
        // Unofficial endpoint; no key required but may change.
        // Use a long-ish range so we can satisfy most user ranges without another call.
        String url = UriComponentsBuilder
                .fromHttpUrl("https://query2.finance.yahoo.com/v8/finance/chart/" + symbol)
                .queryParam("interval", "1d")
                .queryParam("range", "2y")
                .toUriString();

        String json = restTemplate.getForObject(url, String.class);
        if (json == null || json.isBlank()) return List.of();

        try {
            JsonNode root = objectMapper.readTree(json);
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
