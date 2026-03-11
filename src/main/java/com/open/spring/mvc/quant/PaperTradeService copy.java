package com.open.spring.mvc.quant;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.open.spring.mvc.bank.Bank;
import com.open.spring.mvc.bank.BankApiController;

/**
 * Paper trading engine that integrates directly with Bank money.
 *
 * - Uses Bank.balance as cash
 * - Stores portfolio positions in memory (simple)
 * - Uses MarketDataService to get current price
 *
 * IMPORTANT:
 * This is not persistent across server restart (intended for demo + school project).
 */
@Service
public class PaperTradeService {

    private final MarketDataService marketDataService;

    // personId -> portfolio state
    private final Map<Long, PaperPortfolio> portfolios = new HashMap<>();

    public PaperTradeService(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    public Map<String, Object> placeOrder(Bank bank, BankApiController.PaperOrderRequest req) {
        if (bank == null) return Map.of("success", false, "error", "Bank is null");
        if (req == null) return Map.of("success", false, "error", "Missing request body");
        if (req.personId == null) return Map.of("success", false, "error", "Missing personId");
        if (req.ticker == null || req.ticker.isBlank()) return Map.of("success", false, "error", "Missing ticker");
        if (req.qty <= 0) return Map.of("success", false, "error", "qty must be > 0");

        String ticker = req.ticker.trim().toUpperCase(Locale.ROOT);
        String side = (req.side == null) ? "" : req.side.trim().toLowerCase(Locale.ROOT);

        if (!side.equals("buy") && !side.equals("sell")) {
            return Map.of("success", false, "error", "side must be 'buy' or 'sell'");
        }

        double price = getLatestPrice(ticker);
        if (price <= 0) {
            return Map.of("success", false, "error", "Could not fetch market price for " + ticker);
        }

        PaperPortfolio portfolio = portfolios.computeIfAbsent(req.personId, k -> new PaperPortfolio());

        // always sync portfolio cash from bank balance
        portfolio.setCashBalance(bank.getBalance());

        if (side.equals("buy")) {
            return handleBuy(bank, portfolio, ticker, req.qty, price);
        } else {
            return handleSell(bank, portfolio, ticker, req.qty, price);
        }
    }

    public PaperPortfolio getPortfolio(Bank bank) {
        PaperPortfolio empty = new PaperPortfolio();
        if (bank == null) return empty;
        if (bank.getPerson() == null) return empty;
        if (bank.getPerson().getId() == null) return empty;

        Long personId = bank.getPerson().getId();

        PaperPortfolio portfolio = portfolios.computeIfAbsent(personId, k -> new PaperPortfolio());

        // sync cash from bank
        portfolio.setCashBalance(bank.getBalance());

        // update market values
        for (PaperPortfolio.Position pos : portfolio.getPositions().values()) {
            double price = getLatestPrice(pos.getTicker());
            pos.setMarketPrice(round2(price));
            pos.setMarketValue(round2(price * pos.getQty()));
            pos.setUnrealizedPnL(round2((price - pos.getAvgCost()) * pos.getQty()));
        }

        return portfolio;
    }

    // ------------------- Internal Buy/Sell -------------------

    private Map<String, Object> handleBuy(Bank bank, PaperPortfolio portfolio, String ticker, int qty, double price) {
        double cost = qty * price;

        if (bank.getBalance() < cost) {
            return Map.of(
                    "success", false,
                    "error", "Insufficient funds",
                    "needed", round2(cost),
                    "balance", round2(bank.getBalance())
            );
        }

        // subtract money
        bank.setBalance(bank.getBalance() - cost, "paper_trade_buy");
        portfolio.setCashBalance(bank.getBalance());

        PaperPortfolio.Position pos = portfolio.getPositions().getOrDefault(ticker, new PaperPortfolio.Position());
        pos.setTicker(ticker);

        int oldQty = pos.getQty();
        double oldAvg = pos.getAvgCost();

        int newQty = oldQty + qty;
        double newAvg = (oldQty == 0) ? price : ((oldQty * oldAvg) + (qty * price)) / newQty;

        pos.setQty(newQty);
        pos.setAvgCost(round2(newAvg));
        pos.setMarketPrice(round2(price));
        pos.setMarketValue(round2(price * newQty));
        pos.setUnrealizedPnL(round2((price - newAvg) * newQty));

        portfolio.getPositions().put(ticker, pos);

        PaperPortfolio.OrderRecord rec = new PaperPortfolio.OrderRecord();
        rec.setTime(Instant.now().toString());
        rec.setTicker(ticker);
        rec.setSide("BUY");
        rec.setQty(qty);
        rec.setPrice(round2(price));
        rec.setTotalCost(round2(cost));
        portfolio.getOrders().add(rec);

        return Map.of(
                "success", true,
                "message", "Bought " + qty + " shares of " + ticker,
                "ticker", ticker,
                "qty", qty,
                "price", round2(price),
                "balance", round2(bank.getBalance())
        );
    }

    private Map<String, Object> handleSell(Bank bank, PaperPortfolio portfolio, String ticker, int qty, double price) {
        PaperPortfolio.Position pos = portfolio.getPositions().get(ticker);

        if (pos == null || pos.getQty() <= 0) {
            return Map.of("success", false, "error", "No shares owned for " + ticker);
        }

        if (qty > pos.getQty()) {
            return Map.of(
                    "success", false,
                    "error", "Not enough shares",
                    "owned", pos.getQty(),
                    "attempted", qty
            );
        }

        double proceeds = qty * price;

        // add money
        bank.setBalance(bank.getBalance() + proceeds, "paper_trade_sell");
        portfolio.setCashBalance(bank.getBalance());

        int remaining = pos.getQty() - qty;
        pos.setQty(remaining);

        pos.setMarketPrice(round2(price));
        pos.setMarketValue(round2(price * remaining));
        pos.setUnrealizedPnL(round2((price - pos.getAvgCost()) * remaining));

        if (remaining == 0) {
            portfolio.getPositions().remove(ticker);
        } else {
            portfolio.getPositions().put(ticker, pos);
        }

        PaperPortfolio.OrderRecord rec = new PaperPortfolio.OrderRecord();
        rec.setTime(Instant.now().toString());
        rec.setTicker(ticker);
        rec.setSide("SELL");
        rec.setQty(qty);
        rec.setPrice(round2(price));
        rec.setTotalCost(round2(proceeds));
        portfolio.getOrders().add(rec);

        return Map.of(
                "success", true,
                "message", "Sold " + qty + " shares of " + ticker,
                "ticker", ticker,
                "qty", qty,
                "price", round2(price),
                "balance", round2(bank.getBalance())
        );
    }

    // ------------------- Price Fetch -------------------

    private double getLatestPrice(String ticker) {
        try {
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(30);

            List<Bar> bars = marketDataService.getDailyBars(ticker, start, end);
            if (bars == null || bars.isEmpty()) return -1;

            bars.sort(Comparator.comparing(Bar::getTime));
            Bar last = bars.get(bars.size() - 1);

            return last.getClose();
        } catch (Exception e) {
            return -1;
        }
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
