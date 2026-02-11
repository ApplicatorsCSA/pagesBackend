package com.open.spring.mvc.quant;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Simple market data "bar" (OHLCV) used by the quant system.
 * Keep this as a plain POJO/DTO (NOT a JPA @Entity) unless you explicitly
 * want to persist bars in your database.
 *
 * Folder path must match:
 * src/main/java/com/open/spring/mvc/quant/Bar.java
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Bar {

    /** Stock/asset symbol, e.g., "AAPL" */
    private String symbol;

    /** Timestamp for the bar (UTC recommended) */
    private Instant time;

    /** Open price */
    private double open;

    /** High price */
    private double high;

    /** Low price */
    private double low;

    /** Close price */
    private double close;

    /** Volume */
    private long volume;

    /** Optional: timeframe label, e.g., "1d", "1h" */
    private String timeframe;

    /** Convenience: typical price */
    public double typicalPrice() {
        return (high + low + close) / 3.0;
    }

    /** Convenience: bar return (close-open)/open; returns 0 if open==0 */
    public double simpleReturn() {
        return open == 0.0 ? 0.0 : (close - open) / open;
    }
}
