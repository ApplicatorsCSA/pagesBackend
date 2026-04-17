package com.open.spring.mvc.quant;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Value;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pulls historical daily OHLCV from Stooq.
 * Example:
 * https://stooq.com/q/d/l/?s=aapl.us&i=d
 */
@Service
public class MarketDataService {

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Stooq now requires an API key for CSV downloads.
     * Provide via env var `STOOQ_API_KEY` or Spring property `stooq.apiKey`.
     */
    @Value("${stooq.apiKey:${STOOQ_API_KEY:}}")
    private String stooqApiKey;

    public List<Bar> getDailyBars(String ticker, LocalDate start, LocalDate end) {
        String stooqSymbol = toStooqSymbol(ticker);
        String url = "https://stooq.com/q/d/l/?s=" + stooqSymbol + "&i=d";
        if (stooqApiKey != null && !stooqApiKey.isBlank()) {
            url = url + "&apikey=" + stooqApiKey.trim();
        }

        String csv = restTemplate.getForObject(url, String.class);
        if (csv == null || csv.isBlank()) return List.of();

        // Stooq returns a human message when apiKey is missing/invalid.
        String head = csv.stripLeading();
        if (head.startsWith("Get your apikey") || head.contains("get_apikey")) {
            throw new IllegalStateException(
                    "Market data provider requires a STOOQ apiKey. " +
                    "Set env STOOQ_API_KEY (or property stooq.apiKey) on the Spring server."
            );
        }

        List<Bar> bars = parseStooqCsv(csv, ticker);

        List<Bar> filtered = new ArrayList<>();
        for (Bar b : bars) {
            LocalDate d = b.getTime().atZone(ZoneOffset.UTC).toLocalDate();
            if ((d.isEqual(start) || d.isAfter(start)) &&
                (d.isEqual(end) || d.isBefore(end))) {
                filtered.add(b);
            }
        }

        filtered.sort(Comparator.comparing(Bar::getTime));
        return filtered;
    }

    private String toStooqSymbol(String ticker) {
        String t = ticker.trim().toLowerCase();
        if (t.contains(".")) return t;
        return t + ".us";
    }

    private List<Bar> parseStooqCsv(String csv, String ticker) {
        List<Bar> bars = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new StringReader(csv))) {
            String header = br.readLine(); // Date,Open,High,Low,Close,Volume
            if (header == null) return bars;
            if (!header.toLowerCase().contains("date") || !header.toLowerCase().contains("close")) {
                throw new IllegalArgumentException("Unexpected CSV header from provider: " + header);
            }

            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length < 6) continue;

                LocalDate date = LocalDate.parse(p[0]);
                Instant time = date.atStartOfDay().toInstant(ZoneOffset.UTC);

                double open = safeDouble(p[1]);
                double high = safeDouble(p[2]);
                double low = safeDouble(p[3]);
                double close = safeDouble(p[4]);
                long volume = safeLong(p[5]);

                if (Double.isNaN(close) || close <= 0) continue;

                bars.add(new Bar(
                        ticker.toUpperCase(),
                        time,
                        open,
                        high,
                        low,
                        close,
                        volume,
                        "1d"
                ));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse market data CSV from provider", e);
        }

        return bars;
    }

    private double safeDouble(String s) {
        try { return Double.parseDouble(s); }
        catch (Exception e) { return Double.NaN; }
    }

    private long safeLong(String s) {
        try { return Long.parseLong(s); }
        catch (Exception e) { return 0; }
    }
}
