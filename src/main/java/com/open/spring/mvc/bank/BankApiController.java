// src/main/java/com/open/spring/mvc/bank/BankApiController.java
package com.open.spring.mvc.bank;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@RestController
@RequestMapping("/bank")
public class BankApiController {

    @Autowired
    private BankService bankService;

    @Autowired
    private BankJpaRepository bankJpaRepository;

    @Autowired
    private PersonJpaRepository personJpaRepository;

    /**
     * Helper method to find or create a Bank for a given personId
     */
    private Bank findOrCreateBankByPersonId(Long personId) {
        Bank bank = bankJpaRepository.findByPersonId(personId);
        if (bank == null) {
            Person person = personJpaRepository.findById(personId).orElse(null);
            if (person == null) {
                throw new RuntimeException("Person not found with ID: " + personId);
            }
            bank = new Bank(person);
            bank.assessRiskUsingML();
            bank = bankJpaRepository.save(bank);
        }
        return bank;
    }

    // ==========================
    // LEADERBOARD
    // ==========================
    @GetMapping("/leaderboard")
    public ResponseEntity<Map<String, Object>> getLeaderboard() {
        try {
            List<Bank> topBanks = bankJpaRepository.findTop10ByOrderByBalanceDesc();
            List<LeaderboardEntry> leaderboard = new ArrayList<>();

            for (int i = 0; i < topBanks.size(); i++) {
                Bank bank = topBanks.get(i);
                leaderboard.add(new LeaderboardEntry(
                    i + 1,
                    bank.getId(),
                    bank.getUsername() != null ? bank.getUsername() : "User " + bank.getId(),
                    bank.getBalance()
                ));
            }

            return ResponseEntity.ok(Map.of("success", true, "data", leaderboard));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                Map.of("success", false, "error", "Error fetching leaderboard: " + e.getMessage())
            );
        }
    }

    @GetMapping("/leaderboard/search")
    public ResponseEntity<Map<String, Object>> searchLeaderboard(@RequestParam String query) {
        try {
            List<Bank> matchedBanks = bankJpaRepository.findByUidContainingIgnoreCase(query);
            List<LeaderboardEntry> leaderboard = new ArrayList<>();

            for (int i = 0; i < matchedBanks.size(); i++) {
                Bank bank = matchedBanks.get(i);
                leaderboard.add(new LeaderboardEntry(
                    i + 1,
                    bank.getId(),
                    bank.getUsername() != null ? bank.getUsername() : "User " + bank.getId(),
                    bank.getBalance()
                ));
            }

            return ResponseEntity.ok(Map.of("success", true, "data", leaderboard));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                Map.of("success", false, "error", "Error searching leaderboard: " + e.getMessage())
            );
        }
    }

    // ==========================
    // ANALYTICS
    // ==========================
    @GetMapping("/analytics/{userId}")
    public ResponseEntity<Map<String, Object>> getUserAnalytics(@PathVariable Long userId) {
        try {
            Bank bank = bankJpaRepository.findById(userId).orElse(null);
            if (bank == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of("success", false, "error", "User not found")
                );
            }

            Map<String, Object> analyticsData = new HashMap<>();
            analyticsData.put("userId", bank.getId());
            analyticsData.put("username", bank.getUsername() != null ? bank.getUsername() : "User " + bank.getId());
            analyticsData.put("balance", bank.getBalance());
            analyticsData.put("loanAmount", bank.getLoanAmount());
            analyticsData.put("dailyInterestRate", bank.getDailyInterestRate());
            analyticsData.put("riskCategory", bank.getRiskCategory());
            analyticsData.put("riskCategoryString", bank.getRiskCategoryString());
            analyticsData.put("profitMap", bank.getProfitMap());
            analyticsData.put("featureImportance", bank.getFeatureImportance());
            analyticsData.put("featureExplanations", bank.getFeatureImportanceExplanations());
            analyticsData.put("npcProgress", bank.getNpcProgress());

            // NEW game state fields
            analyticsData.put("xp", bank.getXp());
            analyticsData.put("level", bank.getLevel());
            analyticsData.put("questProgress", bank.getQuestProgress());
            analyticsData.put("lastRunStats", bank.getLastRunStats());

            return ResponseEntity.ok(Map.of("success", true, "data", analyticsData));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                Map.of("success", false, "error", "Error fetching user analytics: " + e.getMessage())
            );
        }
    }

    @GetMapping("/analytics/person/{personId}")
    public ResponseEntity<Map<String, Object>> getUserAnalyticsByPersonId(@PathVariable Long personId) {
        try {
            Bank bank = findOrCreateBankByPersonId(personId);

            Map<String, Object> analyticsData = new HashMap<>();
            analyticsData.put("userId", bank.getId());
            analyticsData.put("personId", bank.getPerson().getId());
            analyticsData.put("username", bank.getUsername() != null ? bank.getUsername() : "User " + bank.getId());
            analyticsData.put("balance", bank.getBalance());
            analyticsData.put("loanAmount", bank.getLoanAmount());
            analyticsData.put("dailyInterestRate", bank.getDailyInterestRate());
            analyticsData.put("riskCategory", bank.getRiskCategory());
            analyticsData.put("riskCategoryString", bank.getRiskCategoryString());
            analyticsData.put("profitMap", bank.getProfitMap());
            analyticsData.put("featureImportance", bank.getFeatureImportance());
            analyticsData.put("featureExplanations", bank.getFeatureImportanceExplanations());
            analyticsData.put("npcProgress", bank.getNpcProgress());

            // NEW game state fields
            analyticsData.put("xp", bank.getXp());
            analyticsData.put("level", bank.getLevel());
            analyticsData.put("questProgress", bank.getQuestProgress());
            analyticsData.put("lastRunStats", bank.getLastRunStats());

            return ResponseEntity.ok(Map.of("success", true, "data", analyticsData));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                Map.of("success", false, "error", "Error fetching user analytics: " + e.getMessage())
            );
        }
    }

    // ==========================
    // GAME ENDPOINTS (NEW)
    // ==========================
    @GetMapping("/game/status/person/{personId}")
    public ResponseEntity<Map<String, Object>> getGameStatus(@PathVariable Long personId) {
        try {
            Bank bank = findOrCreateBankByPersonId(personId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "personId", personId,
                "userId", bank.getId(),
                "username", bank.getUsername() != null ? bank.getUsername() : "User " + bank.getId(),
                "balance", bank.getBalance(),
                "xp", bank.getXp(),
                "level", bank.getLevel(),
                "questProgress", bank.getQuestProgress(),
                "npcProgress", bank.getNpcProgress(),
                "riskCategory", bank.getRiskCategory(),
                "riskCategoryString", bank.getRiskCategoryString(),
                "dailyInterestRate", bank.getDailyInterestRate(),
                "lastRunStats", bank.getLastRunStats()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                Map.of("success", false, "error", "Error fetching game status: " + e.getMessage())
            );
        }
    }

    @PostMapping("/game/completeQuest")
    public ResponseEntity<Map<String, Object>> completeQuest(@RequestBody QuestCompleteRequest request) {
        try {
            if (request.getPersonId() == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    Map.of("success", false, "error", "personId is required")
                );
            }
            if (request.getQuestName() == null || request.getQuestName().isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    Map.of("success", false, "error", "questName is required")
                );
            }

            Bank bank = findOrCreateBankByPersonId(request.getPersonId());

            LinkedHashMap<String, Boolean> qp = bank.getQuestProgress();
            if (qp == null) qp = new LinkedHashMap<>();

            // mark quest complete
            qp.put(request.getQuestName(), true);
            bank.setQuestProgress(qp);

            // xp & leveling
            int xpGained = request.getXp() != null ? request.getXp() : 50;
            if (xpGained < 0) xpGained = 0;
            bank.addXp(xpGained);

            bankJpaRepository.save(bank);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "xp", bank.getXp(),
                "level", bank.getLevel(),
                "questProgress", bank.getQuestProgress()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                Map.of("success", false, "error", "Error completing quest: " + e.getMessage())
            );
        }
    }

    // Optional: let quant endpoints write last run stats (frontend can also call this)
    @PostMapping("/game/lastRunStats")
    public ResponseEntity<Map<String, Object>> setLastRunStats(@RequestBody LastRunStatsRequest request) {
        try {
            if (request.getPersonId() == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    Map.of("success", false, "error", "personId is required")
                );
            }

            Bank bank = findOrCreateBankByPersonId(request.getPersonId());

            Map<String, Object> stats = bank.getLastRunStats();
            if (stats == null) stats = new HashMap<>();

            if (request.getStats() != null) {
                stats.putAll(request.getStats());
            }

            bank.setLastRunStats(stats);
            bankJpaRepository.save(bank);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "lastRunStats", bank.getLastRunStats()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                Map.of("success", false, "error", "Error updating lastRunStats: " + e.getMessage())
            );
        }
    }

    // ==========================
    // LOANS / INTEREST / NPC
    // ==========================
    @GetMapping("/{id}/profitmap/{category}")
    public ResponseEntity<List<List<Object>>> getProfitByCategory(@PathVariable Long id, @PathVariable String category) {
        try {
            Bank bank = findOrCreateBankByPersonId(id);
            return ResponseEntity.ok(bank.getProfitByCategory(category));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{id}/interestRate")
    public ResponseEntity<Double> getInterestRate(@PathVariable Long id) {
        try {
            Bank bank = findOrCreateBankByPersonId(id);
            return ResponseEntity.ok(bank.getDailyInterestRate());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/requestLoan")
    public ResponseEntity<String> requestLoan(@RequestBody LoanRequest request) {
        try {
            Bank bank = bankService.requestLoan(request.getPersonId(), request.getLoanAmount());
            return ResponseEntity.ok("Loan of amount " + request.getLoanAmount() + " granted to user with Person ID: " + request.getPersonId());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Loan request failed: " + e.getMessage());
        }
    }

    @PostMapping("/repayLoan")
    public ResponseEntity<String> repayLoan(@RequestBody RepaymentRequest request) {
        try {
            Bank bank = bankService.repayLoan(request.getPersonId(), request.getRepaymentAmount());
            return ResponseEntity.ok("Loan repayment of amount " + request.getRepaymentAmount()
                    + " processed for user with Person ID: " + request.getPersonId()
                    + ". Remaining loan amount: " + bank.getLoanAmount());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Loan repayment failed: " + e.getMessage());
        }
    }

    @GetMapping("/{personId}/loanAmount")
    public ResponseEntity<Double> getLoanAmount(@PathVariable Long personId) {
        try {
            Bank bank = findOrCreateBankByPersonId(personId);
            return ResponseEntity.ok(bank.getLoanAmount());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @Scheduled(fixedRate = 86400000)
    public void scheduledInterestApplication() {
        applyInterestToAllLoans();
    }

    @PostMapping("/newLoanAmountInterest")
    public String applyInterestToAllLoans() {
        List<Bank> allBanks = bankJpaRepository.findAll();
        for (Bank bank : allBanks) {
            bank.setLoanAmount(bank.getLoanAmount() * 1.05);
        }
        bankJpaRepository.saveAll(allBanks);
        return "Applied 5% interest to all loan amounts.";
    }

    @GetMapping("/{personId}/npcProgress")
    public ResponseEntity<LinkedHashMap<String, Boolean>> getNpcProgress(@PathVariable Long personId) {
        try {
            Bank bank = findOrCreateBankByPersonId(personId);
            return ResponseEntity.ok((LinkedHashMap<String, Boolean>) bank.getNpcProgress());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @PostMapping("/updateNpcProgress")
    public ResponseEntity<LinkedHashMap<String, Boolean>> updateNpcProgress(@RequestBody npcProgress request) {
        try {
            String justCompletedNpc = request.getNpcId();
            if (justCompletedNpc == null || justCompletedNpc.isBlank()) {
                return ResponseEntity.badRequest().build();
            }

            Bank bank = findOrCreateBankByPersonId(request.getPersonId());
            LinkedHashMap<String, Boolean> progressMap = bank.getNpcProgress();

            boolean found = false;
            Iterator<Map.Entry<String, Boolean>> iter = progressMap.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<String, Boolean> entry = iter.next();
                if (!found) {
                    if (entry.getKey().equals(justCompletedNpc)) {
                        found = true;
                        entry.setValue(false); // mark current as done (optional)
                    }
                } else {
                    entry.setValue(true); // unlock next
                    break;
                }
            }

            bank.setNpcProgress(progressMap);
            bankJpaRepository.save(bank);

            @SuppressWarnings("unchecked")
            LinkedHashMap<String, Boolean> result = (LinkedHashMap<String, Boolean>) bank.getNpcProgress();
            return Res
