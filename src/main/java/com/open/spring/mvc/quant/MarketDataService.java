package com.open.spring.mvc.quant;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pulls historical daily OHLCV from Stooq (free, no API key).
 * Example:
 * https://stooq.com/q/d/l/?s=aapl.us&i=d
 */
@Service
public class MarketDataService {

    private final RestTemplate restTemplate = new RestTemplate();

    public List<Bar> getDailyBars(String ticker, LocalDate start, LocalDate end) {
        String stooqSymbol = toStooqSymbol(ticker);
        String url = "https://stooq.com/q/d/l/?s=" + stooqSymbol + "&i=d";

        String csv = restTemplate.getForObject(url, String.class);
        if (csv == null || csv.isBlank()) return List.of();

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
        } catch (Exception ignored) {}

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
