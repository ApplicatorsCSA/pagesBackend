package com.open.spring.mvc.quant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pulls historical daily OHLCV from Alpha Vantage.
 *
 * Uses:
 * - TIME_SERIES_DAILY_ADJUSTED (daily OHLCV, with corporate actions included)
 *
 * Config:
 * - env var `ALPHAVANTAGE_API_KEY` or Spring property `alphavantage.apiKey`
 */
@Service
public class MarketDataService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Alpha Vantage API key.
     * Provide via env var `ALPHAVANTAGE_API_KEY` or Spring property `alphavantage.apiKey`.
     */
    @Value("${alphavantage.apiKey:${ALPHAVANTAGE_API_KEY:}}")
    private String alphaVantageApiKey;

    public List<Bar> getDailyBars(String ticker, LocalDate start, LocalDate end) {
        String sym = (ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT));
        if (sym.isBlank()) throw new IllegalArgumentException("ticker is required");
        if (start == null || end == null) throw new IllegalArgumentException("start/end are required");
        if (end.isBefore(start)) throw new IllegalArgumentException("end must be >= start");

        String key = alphaVantageApiKey == null ? "" : alphaVantageApiKey.trim();
        if (key.isBlank()) {
            throw new IllegalStateException(
                    "Missing Alpha Vantage API key. Set env ALPHAVANTAGE_API_KEY (or property alphavantage.apiKey) on the Spring server."
            );
        }

        // Alpha Vantage returns latest first; we'll sort ascending at the end.
        String url = "https://www.alphavantage.co/query"
                + "?function=TIME_SERIES_DAILY_ADJUSTED"
                + "&symbol=" + sym
                + "&outputsize=full"
                + "&apikey=" + key;

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
            throw new IllegalStateException("Alpha Vantage error: " + root.get("Error Message").asText());
        }
        if (root.hasNonNull("Note")) {
            throw new IllegalStateException("Alpha Vantage throttle: " + root.get("Note").asText());
        }
        if (root.hasNonNull("Information")) {
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
                if (d.isBefore(start) || d.isAfter(end)) return;

                JsonNode row = entry.getValue();
                double open = row.path("1. open").asDouble(Double.NaN);
                double high = row.path("2. high").asDouble(Double.NaN);
                double low = row.path("3. low").asDouble(Double.NaN);
                double close = row.path("4. close").asDouble(Double.NaN);
                long volume = row.path("6. volume").asLong(0);

                if (!Double.isFinite(close) || close <= 0) return;
                Instant t = d.atStartOfDay().toInstant(ZoneOffset.UTC);
                out.add(new Bar(sym, t, open, high, low, close, volume, "1d"));
            } catch (Exception ignored) {
                // skip malformed rows
            }
        });

        out.sort(Comparator.comparing(Bar::getTime));
        return out;
    }
}
