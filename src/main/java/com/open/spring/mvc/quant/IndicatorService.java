package com.open.spring.mvc.quant;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Computes indicators used by the BankApiController:
 * - Moving averages (short/long)
 * - RSI
 * - Bollinger Bands
 * - MACD (fast/slow, signal=9, histogram)
 *
 * Returns a Map with arrays aligned to the bars list index.
 */
@Service
public class IndicatorService {

    public Map<String, Object> calculateAll(
            List<Bar> bars,
            int maShort,
            int maLong,
            int rsiPeriod,
            int bbPeriod,
            int macdFast,
            int macdSlow
    ) {
        int n = (bars == null) ? 0 : bars.size();
        List<Double> close = new ArrayList<>(n);
        for (Bar b : bars) close.add(b.getClose());

        List<Double> maS = sma(close, maShort);
        List<Double> maL = sma(close, maLong);

        List<Double> rsi = rsi(close, rsiPeriod);

        Bollinger bb = bollinger(close, bbPeriod, 2.0);

        Macd macd = macd(close, macdFast, macdSlow, 9);

        // Signals (simple)
        Map<String, Object> signals = new HashMap<>();
        signals.put("maSignal", maSignal(maS, maL));
        signals.put("rsiSignal", rsiSignal(rsi));
        signals.put("macdSignal", macdSignal(macd.macd, macd.signal));

        Map<String, Object> out = new HashMap<>();
        out.put("bars", bars);

        out.put("MA_short", maS);
        out.put("MA_long", maL);

        out.put("RSI", rsi);

        out.put("BB_upper", bb.upper);
        out.put("BB_middle", bb.middle);
        out.put("BB_lower", bb.lower);

        out.put("MACD", macd.macd);
        out.put("MACD_signal", macd.signal);
        out.put("MACD_histogram", macd.hist);

        out.put("signals", signals);
        return out;
    }

    // -------------------------
    // Simple signals
    // -------------------------

    private int maSignal(List<Double> maS, List<Double> maL) {
        int n = Math.min(maS.size(), maL.size());
        if (n < 2) return 0;
        Double s1 = maS.get(n - 2), s2 = maS.get(n - 1);
        Double l1 = maL.get(n - 2), l2 = maL.get(n - 1);
        if (s1 == null || s2 == null || l1 == null || l2 == null) return 0;

        boolean crossedUp = s1 <= l1 && s2 > l2;
        boolean crossedDown = s1 >= l1 && s2 < l2;

        if (crossedUp) return 1;
        if (crossedDown) return -1;
        return 0;
    }

    private int rsiSignal(List<Double> rsi) {
        if (rsi.isEmpty()) return 0;
        Double last = rsi.get(rsi.size() - 1);
        if (last == null) return 0;
        if (last < 30) return 1;   // oversold -> buy
        if (last > 70) return -1;  // overbought -> sell
        return 0;
    }

    private int macdSignal(List<Double> macd, List<Double> signal) {
        int n = Math.min(macd.size(), signal.size());
        if (n < 2) return 0;
        Double m1 = macd.get(n - 2), m2 = macd.get(n - 1);
        Double s1 = signal.get(n - 2), s2 = signal.get(n - 1);
        if (m1 == null || m2 == null || s1 == null || s2 == null) return 0;

        boolean crossedUp = m1 <= s1 && m2 > s2;
        boolean crossedDown = m1 >= s1 && m2 < s2;

        if (crossedUp) return 1;
        if (crossedDown) return -1;
        return 0;
    }

    // -------------------------
    // Indicator implementations
    // -------------------------

    private List<Double> sma(List<Double> values, int period) {
        int n = values.size();
        List<Double> out = new ArrayList<>(Collections.nCopies(n, null));
        if (period <= 1) {
            for (int i = 0; i < n; i++) out.set(i, values.get(i));
            return out;
        }

        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            Double v = values.get(i);
            if (v == null) continue;
            sum += v;

            if (i >= period) {
                Double toRemove = values.get(i - period);
                if (toRemove != null) sum -= toRemove;
            }

            if (i >= period - 1) {
                out.set(i, sum / period);
            }
        }
        return out;
    }

    private List<Double> rsi(List<Double> close, int period) {
        int n = close.size();
        List<Double> out = new ArrayList<>(Collections.nCopies(n, null));
        if (n < period + 1) return out;

        double gain = 0.0, loss = 0.0;

        // seed
        for (int i = 1; i <= period; i++) {
            double diff = close.get(i) - close.get(i - 1);
            if (diff >= 0) gain += diff;
            else loss -= diff;
        }

        double avgGain = gain / period;
        double avgLoss = loss / period;

        out.set(period, rsiFromAverages(avgGain, avgLoss));

        // Wilder smoothing
        for (int i = period + 1; i < n; i++) {
            double diff = close.get(i) - close.get(i - 1);
            double g = Math.max(diff, 0);
            double l = Math.max(-diff, 0);

            avgGain = ((avgGain * (period - 1)) + g) / period;
            avgLoss = ((avgLoss * (period - 1)) + l) / period;

            out.set(i, rsiFromAverages(avgGain, avgLoss));
        }

        return out;
    }

    private double rsiFromAverages(double avgGain, double avgLoss) {
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private static class Bollinger {
        List<Double> upper, middle, lower;
        Bollinger(List<Double> u, List<Double> m, List<Double> l) {
            upper = u; middle = m; lower = l;
        }
    }

    private Bollinger bollinger(List<Double> close, int period, double k) {
        int n = close.size();
        List<Double> mid = sma(close, period);
        List<Double> upper = new ArrayList<>(Collections.nCopies(n, null));
        List<Double> lower = new ArrayList<>(Collections.nCopies(n, null));

        if (period <= 1) {
            for (int i = 0; i < n; i++) {
                Double c = close.get(i);
                upper.set(i, c);
                lower.set(i, c);
            }
            return new Bollinger(upper, mid, lower);
        }

        for (int i = period - 1; i < n; i++) {
            Double mean = mid.get(i);
            if (mean == null) continue;

            double var = 0.0;
            for (int j = i - period + 1; j <= i; j++) {
                double d = close.get(j) - mean;
                var += d * d;
            }
            double std = Math.sqrt(var / period);

            upper.set(i, mean + k * std);
            lower.set(i, mean - k * std);
        }

        return new Bollinger(upper, mid, lower);
    }

    private static class Macd {
        List<Double> macd, signal, hist;
        Macd(List<Double> m, List<Double> s, List<Double> h) {
            macd = m; signal = s; hist = h;
        }
    }

    private Macd macd(List<Double> close, int fast, int slow, int signalPeriod) {
        int n = close.size();

        List<Double> emaFast = ema(close, fast);
        List<Double> emaSlow = ema(close, slow);

        List<Double> macdLine = new ArrayList<>(Collections.nCopies(n, null));
        for (int i = 0; i < n; i++) {
            Double f = emaFast.get(i);
            Double s = emaSlow.get(i);
            if (f != null && s != null) macdLine.set(i, f - s);
        }

        List<Double> signal = ema(macdLine, signalPeriod);

        List<Double> hist = new ArrayList<>(Collections.nCopies(n, null));
        for (int i = 0; i < n; i++) {
            Double m = macdLine.get(i);
            Double sig = signal.get(i);
            if (m != null && sig != null) hist.set(i, m - sig);
        }

        return new Macd(macdLine, signal, hist);
    }

    private List<Double> ema(List<Double> values, int period) {
        int n = values.size();
        List<Double> out = new ArrayList<>(Collections.nCopies(n, null));
        if (n == 0) return out;
        if (period <= 1) {
            for (int i = 0; i < n; i++) out.set(i, values.get(i));
            return out;
        }

        double alpha = 2.0 / (period + 1.0);

        // find first non-null
        int start = -1;
        for (int i = 0; i < n; i++) {
            if (values.get(i) != null) { start = i; break; }
        }
        if (start == -1) return out;

        double prev = values.get(start);
        out.set(start, prev);

        for (int i = start + 1; i < n; i++) {
            Double v = values.get(i);
            if (v == null) {
                out.set(i, prev);
                continue;
            }
            prev = alpha * v + (1 - alpha) * prev;
            out.set(i, prev);
        }

        return out;
    }
}
