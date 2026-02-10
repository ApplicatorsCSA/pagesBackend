package com.open.spring.mvc.quant;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.open.spring.mvc.bank.BankApiController;

/**
 * Backtesting engine:
 * Strategies: ma / rsi / macd / ml
 */
@Service
public class BacktestService {

    private final IndicatorService indicatorService;

    public BacktestService(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;
    }

    public BacktestResult run(BankApiController.BacktestRequest req, List<Bar> bars, Object indicatorsMaybe) {
        BacktestResult out = new BacktestResult();

        if (bars == null || bars.size() < 60) {
            out.setNote("Not enough market bars for backtest (need ~60+).");
            return out;
        }

        // Sort bars by time
        List<Bar> data = new ArrayList<>(bars);
        data.sort(Comparator.comparing(Bar::getTime));

        String strategy = safeLower(req.strategy);
        double initial = Math.max(1.0, req.initialCapital);
        double positionPct = clamp(req.positionPct, 0.01, 1.0);
        double stopLoss = clamp(req.stopLoss, 0.0, 0.5);
        double takeProfit = clamp(req.takeProfit, 0.0, 2.0);
        double commission = clamp(req.commission, 0.0, 0.02);

        double cash = initial;
        int shares = 0;
        double entryPrice = 0.0;

        double benchShares = initial / data.get(0).getClose();

        // compute indicators from IndicatorService
        Map<String, Object> indMap = indicatorService.calculateAll(data, 20, 50, 14, 20, 12, 26);

        List<Double> maShort = (List<Double>) indMap.get("MA_short");
        List<Double> maLong = (List<Double>) indMap.get("MA_long");
        List<Double> rsi = (List<Double>) indMap.get("RSI");
        List<Double> macd = (List<Double>) indMap.get("MACD");
        List<Double> macdSignal = (List<Double>) indMap.get("MACD_signal");

        double peak = initial;
        double maxDD = 0.0;

        for (int i = 1; i < data.size(); i++) {
            Bar b = data.get(i);
            double price = b.getClose();
            LocalDate date = b.getTime().atZone(ZoneOffset.UTC).toLocalDate();

            double pv = cash + shares * price;

            // stoploss/takeprofit
            if (shares > 0 && entryPrice > 0) {
                double move = (price / entryPrice) - 1.0;

                if (stopLoss > 0 && move <= -stopLoss) {
                    cash += shares * price * (1.0 - commission);
                    recordTrade(out, date, "SELL", shares, price, cash, 0);
                    shares = 0;
                    entryPrice = 0;
                    pv = cash;
                } else if (takeProfit > 0 && move >= takeProfit) {
                    cash += shares * price * (1.0 - commission);
                    recordTrade(out, date, "SELL", shares, price, cash, 0);
                    shares = 0;
                    entryPrice = 0;
                    pv = cash;
                }
            }

            int signal = signalForDay(strategy, i, data, maShort, maLong, rsi, macd, macdSignal);

            if (signal == 1 && shares == 0) {
                double budget = cash * positionPct;
                int qty = (int) Math.floor(budget / price);

                if (qty > 0) {
                    double cost = qty * price * (1.0 + commission);
                    if (cost <= cash) {
                        cash -= cost;
                        shares += qty;
                        entryPrice = price;
                        recordTrade(out, date, "BUY", qty, price, cash, shares);
                    }
                }
            } else if (signal == -1 && shares > 0) {
                cash += shares * price * (1.0 - commission);
                recordTrade(out, date, "SELL", shares, price, cash, 0);
                shares = 0;
                entryPrice = 0;
            }

            double portfolio = cash + shares * price;
            double benchmark = benchShares * price;

            out.getDates().add(date.toString());
            out.getPortfolioValue().add(round2(portfolio));
            out.getBenchmarkValue().add(round2(benchmark));

            if (out.getPortfolioValue().size() > 1) {
                double prev = out.getPortfolioValue().get(out.getPortfolioValue().size() - 2);
                double ret = prev > 0 ? (portfolio / prev) - 1.0 : 0.0;
                out.getReturns().add(ret);
            }

            peak = Math.max(peak, portfolio);
            double dd = peak > 0 ? (portfolio / peak) - 1.0 : 0.0;
            maxDD = Math.min(maxDD, dd);
        }

        double finalPV = out.getPortfolioValue().isEmpty() ? initial : out.getPortfolioValue().get(out.getPortfolioValue().size() - 1);
        out.setTotalReturnPct(((finalPV / initial) - 1.0) * 100.0);
        out.setMaxDrawdownPct(Math.abs(maxDD) * 100.0);
        out.setWinRatePct(calcWinRate(out.getTrades()));
        out.setNote("Backtest complete.");
        return out;
    }

    // ---------------- Signals ----------------

    private int signalForDay(
            String strategy,
            int i,
            List<Bar> data,
            List<Double> maShort,
            List<Double> maLong,
            List<Double> rsi,
            List<Double> macd,
            List<Double> macdSignal
    ) {
        switch (strategy) {
            case "ma":
                return maSignal(i, maShort, maLong);
            case "rsi":
                return rsiSignal(i, rsi);
            case "macd":
                return macdSignal(i, macd, macdSignal);
            case "ml":
                return momentumSignal(i, data);
            default:
                return 0;
        }
    }

    private int maSignal(int i, List<Double> maS, List<Double> maL) {
        if (i <= 0) return 0;
        Double sPrev = maS.get(i - 1);
        Double lPrev = maL.get(i - 1);
        Double sNow = maS.get(i);
        Double lNow = maL.get(i);

        if (sPrev == null || lPrev == null || sNow == null || lNow == null) return 0;

        if (sPrev <= lPrev && sNow > lNow) return 1;
        if (sPrev >= lPrev && sNow < lNow) return -1;
        return 0;
    }

    private int rsiSignal(int i, List<Double> rsi) {
        Double v = rsi.get(i);
        if (v == null) return 0;
        if (v <= 30) return 1;
        if (v >= 70) return -1;
        return 0;
    }

    private int macdSignal(int i, List<Double> macd, List<Double> sig) {
        if (i <= 0) return 0;

        Double mPrev = macd.get(i - 1);
        Double sPrev = sig.get(i - 1);
        Double mNow = macd.get(i);
        Double sNow = sig.get(i);

        if (mPrev == null || sPrev == null || mNow == null || sNow == null) return 0;

        if (mPrev <= sPrev && mNow > sNow) return 1;
        if (mPrev >= sPrev && mNow < sNow) return -1;
        return 0;
    }

    // simple momentum placeholder for ML strategy
    private int momentumSignal(int i, List<Bar> data) {
        if (i < 20) return 0;
        double now = data.get(i).getClose();
        double prev = data.get(i - 20).getClose();
        if (prev <= 0) return 0;

        double mom = (now / prev) - 1.0;
        if (mom > 0.02) return 1;
        if (mom < -0.02) return -1;
        return 0;
    }

    // ---------------- Helpers ----------------

    private void recordTrade(BacktestResult out, LocalDate date, String side, int qty, double price, double cashAfter, double posAfter) {
        BacktestResult.Trade t = new BacktestResult.Trade();
        t.setDate(date.toString());
        t.setSide(side);
        t.setQty(qty);
        t.setPrice(round2(price));
        t.setCashAfter(round2(cashAfter));
        t.setPositionAfter(round2(posAfter));
        out.getTrades().add(t);
    }

    private double calcWinRate(List<BacktestResult.Trade> trades) {
        double lastBuyPrice = -1;
        int wins = 0;
        int closed = 0;

        for (BacktestResult.Trade t : trades) {
            if ("BUY".equalsIgnoreCase(t.getSide())) {
                lastBuyPrice = t.getPrice();
            } else if ("SELL".equalsIgnoreCase(t.getSide()) && lastBuyPrice > 0) {
                closed++;
                if (t.getPrice() > lastBuyPrice) wins++;
                lastBuyPrice = -1;
            }
        }

        if (closed == 0) return 0.0;
        return (wins * 100.0) / closed;
    }

    private String safeLower(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
