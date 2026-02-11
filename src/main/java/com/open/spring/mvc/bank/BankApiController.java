package com.open.spring.mvc.bank;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.quant.BacktestService;
import com.open.spring.mvc.quant.IndicatorService;
import com.open.spring.mvc.quant.MLService;
import com.open.spring.mvc.quant.MarketDataService;
import com.open.spring.mvc.quant.NewsService;
import com.open.spring.mvc.quant.PaperTradeService;

import lombok.Data;

/**
 * Bank API = the ONE backend surface area your frontend uses.
 * Quant endpoints live inside this controller under /bank/quant/*
 */
@RestController
@RequestMapping({ "/bank", "/api/bank" })
public class BankApiController {

    // ===== Existing Bank deps =====
    private final BankJpaRepository bankRepo;

    // ===== Quant deps (all "quant features should be there") =====
    private final MarketDataService marketDataService;
    private final IndicatorService indicatorService;
    private final MLService mlService;
    private final NewsService newsService;
    private final BacktestService backtestService;
    private final PaperTradeService paperTradeService;

    public BankApiController(
            BankJpaRepository bankRepo,
            MarketDataService marketDataService,
            IndicatorService indicatorService,
            MLService mlService,
            NewsService newsService,
            BacktestService backtestService,
            PaperTradeService paperTradeService) {
        this.bankRepo = bankRepo;
        this.marketDataService = marketDataService;
        this.indicatorService = indicatorService;
        this.mlService = mlService;
        this.newsService = newsService;
        this.backtestService = backtestService;
        this.paperTradeService = paperTradeService;
    }

    // ============================================================
    // BANK CORE API
    // ============================================================
    // NOTE: keep your existing bank endpoints here.
    // If you already have these implemented, KEEP YOUR LOGIC and only add the Quant
    // section below.

    @GetMapping("/byPerson")
    public ResponseEntity<?> getBank(@RequestParam Long personId) {
        Bank bank = bankRepo.findByPersonId(personId);
        if (bank == null)
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Bank not found"));
        return ResponseEntity.ok(bank);
    }

    @PostMapping("/deposit")
    public ResponseEntity<?> deposit(@RequestBody MoneyRequest req) {
        Bank bank = bankRepo.findByPersonId(req.personId);
        if (bank == null)
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Bank not found"));

        if (req.amount <= 0)
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "amount must be > 0"));
        bank.setBalance(bank.getBalance() + req.amount);

        bankRepo.save(bank);
        return ResponseEntity.ok(Map.of("success", true, "balance", bank.getBalance()));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<?> withdraw(@RequestBody MoneyRequest req) {
        Bank bank = bankRepo.findByPersonId(req.personId);
        if (bank == null)
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Bank not found"));

        if (req.amount <= 0)
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "amount must be > 0"));
        if (bank.getBalance() < req.amount)
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "insufficient funds"));

        bank.setBalance(bank.getBalance() - req.amount);

        bankRepo.save(bank);
        return ResponseEntity.ok(Map.of("success", true, "balance", bank.getBalance()));
    }

    @Data
    public static class MoneyRequest {
        public Long personId;
        public double amount;
    }

    // ============================================================
    // QUANT TRADING SYSTEM (INSIDE BANK API)
    // Everything is under /bank/quant/* so the frontend only uses Bank API.
    // ============================================================

    // 1) Market data: daily OHLCV
    // GET /bank/quant/market/history?ticker=AAPL&start=2024-01-01&end=2026-02-09
    @GetMapping("/quant/market/history")
    public ResponseEntity<?> quantHistory(
            @RequestParam String ticker,
            @RequestParam String start,
            @RequestParam String end) {
        LocalDate s = LocalDate.parse(start);
        LocalDate e = LocalDate.parse(end);
        return ResponseEntity.ok(marketDataService.getDailyBars(ticker, s, e));
    }

    // 2) Indicators (MA/RSI/BB/MACD)
    // POST /bank/quant/indicators/calc
    @PostMapping("/quant/indicators/calc")
    public ResponseEntity<?> quantIndicators(@RequestBody IndicatorsRequest req) {
        var bars = marketDataService.getDailyBars(req.ticker, req.start, req.end);
        var out = indicatorService.calculateAll(
                bars,
                req.maShort, req.maLong,
                req.rsiPeriod,
                req.bbPeriod,
                req.macdFast, req.macdSlow);
        return ResponseEntity.ok(out);
    }

    // 3) News sentiment features (overall + categories + headlines)
    // GET /bank/quant/news/sentiment?ticker=AAPL
    @GetMapping("/quant/news/sentiment")
    public ResponseEntity<?> quantNews(@RequestParam String ticker) {
        return ResponseEntity.ok(newsService.getSentimentSnapshot(ticker));
    }

    // 4) ML train + predict (LR/RF; LSTM hook)
    // POST /bank/quant/ml/train
    @PostMapping("/quant/ml/train")
    public ResponseEntity<?> quantTrain(@RequestBody TrainRequest req) {
        var bars = marketDataService.getDailyBars(req.ticker, req.start, req.end);
        var indicators = indicatorService.calculateAll(bars, 20, 50, 14, 20, 12, 26);
        var news = newsService.getSentimentSnapshot(req.ticker);
        return ResponseEntity.ok(mlService.trainAndPredict(req, bars, indicators, news));
    }

    // 5) Backtesting (MA/RSI/MACD/ML signals)
    // POST /bank/quant/backtest/run
    @PostMapping("/quant/backtest/run")
    public ResponseEntity<?> quantBacktest(@RequestBody BacktestRequest req) {
        var bars = marketDataService.getDailyBars(req.ticker, req.start, req.end);
        var indicators = indicatorService.calculateAll(bars, 20, 50, 14, 20, 12, 26);
        return ResponseEntity.ok(backtestService.run(req, bars, indicators));
    }

    // 6) Paper trading order: updates "bank game money" instantly
    // POST /bank/quant/paper/order
    @PostMapping("/quant/paper/order")
    public ResponseEntity<?> quantPaperOrder(@RequestBody PaperOrderRequest req) {
        Bank bank = bankRepo.findByPersonId(req.personId);
        if (bank == null)
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Bank not found"));

        var result = paperTradeService.placeOrder(bank, req);

        // ensure balance changes persist
        bankRepo.save(bank);

        return ResponseEntity.ok(result);
    }

    // 7) Paper portfolio snapshot
    // GET /bank/quant/paper/portfolio?personId=123
    @GetMapping("/quant/paper/portfolio")
    public ResponseEntity<?> quantPortfolio(@RequestParam Long personId) {
        Bank bank = bankRepo.findByPersonId(personId);
        if (bank == null)
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Bank not found"));
        return ResponseEntity.ok(paperTradeService.getPortfolio(bank));
    }

    // -------------------- Request DTOs --------------------

    @Data
    public static class IndicatorsRequest {
        public String ticker;
        public LocalDate start;
        public LocalDate end;
        public int maShort = 20;
        public int maLong = 50;
        public int rsiPeriod = 14;
        public int bbPeriod = 20;
        public int macdFast = 12;
        public int macdSlow = 26;
    }

    @Data
    public static class TrainRequest {
        public String ticker;
        public LocalDate start;
        public LocalDate end;
        public String modelType; // "linear_regression" | "random_forest" | "lstm"
        public int lookback = 60;
        public int horizon = 5;
        public double testSize = 0.2;
    }

    @Data
    public static class BacktestRequest {
        public String ticker;
        public LocalDate start;
        public LocalDate end;
        public String strategy; // "ma" | "rsi" | "macd" | "ml"
        public double initialCapital = 10000;
        public double positionPct = 1.0;
        public double stopLoss = 0.05;
        public double takeProfit = 0.10;
        public double commission = 0.001;
    }

    @Data
    public static class PaperOrderRequest {
        public Long personId;
        public String ticker;
        public String side; // "buy" or "sell"
        public int qty;
        public String type; // "market" only for now
    }
}
