// src/main/java/com/open/spring/mvc/bank/BankService.java
package com.open.spring.mvc.bank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankService {

    private static final Logger logger = LoggerFactory.getLogger(BankService.class);

    @Autowired
    private BankJpaRepository bankRepository;

    public Bank findByPersonId(Long personId) {
        Bank bank = bankRepository.findByPersonId(personId);
        if (bank == null) {
            logger.error("No bank account found for Person ID: {}", personId);
            throw new RuntimeException("Bank account not found for Person ID: " + personId);
        }
        return bank;
    }

    @Transactional
    public void clearAllBanks() {
        try {
            logger.info("Starting to clear all bank records...");

            long initialCount = bankRepository.count();
            logger.info("Initial bank record count: {}", initialCount);

            if (initialCount == 0) {
                logger.info("No bank records to delete");
                return;
            }

            bankRepository.deleteAll();
            bankRepository.flush();

            long finalCount = bankRepository.count();
            logger.info("Final bank record count after deletion: {}", finalCount);

            if (finalCount > 0) {
                logger.warn("Some records were not deleted. Attempting alternative deletion method...");
                clearAllBanksByBatch();
            } else {
                logger.info("Successfully cleared all {} bank records", initialCount);
            }

        } catch (Exception e) {
            logger.error("Error clearing bank records: ", e);
            throw new RuntimeException("Failed to clear bank records: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void clearAllBanksByBatch() {
        try {
            logger.info("Attempting batch deletion of bank records...");

            var allBanks = bankRepository.findAll();
            logger.info("Found {} bank records to delete", allBanks.size());

            int batchSize = 50;
            for (int i = 0; i < allBanks.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, allBanks.size());
                var batch = allBanks.subList(i, endIndex);

                logger.info("Deleting batch {}-{} of {}", i + 1, endIndex, allBanks.size());

                for (Bank bank : batch) {
                    try {
                        if (bank.getPerson() != null) {
                            bank.getPerson().setBanks(null);
                            bank.setPerson(null);
                        }
                        bankRepository.delete(bank);
                    } catch (Exception e) {
                        logger.error("Failed to delete bank with ID: {}", bank.getId(), e);
                    }
                }

                bankRepository.flush();
            }

            long remainingCount = bankRepository.count();
            logger.info("Remaining bank records after batch deletion: {}", remainingCount);

        } catch (Exception e) {
            logger.error("Error in batch deletion: ", e);
            throw new RuntimeException("Failed to clear bank records via batch deletion: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void clearAllBanksForce() {
        try {
            logger.info("Attempting force deletion using native SQL...");

            var allBanks = bankRepository.findAll();

            for (Bank bank : allBanks) {
                try {
                    if (bank.getPerson() != null) {
                        bank.getPerson().setBanks(null);
                    }
                    bank.setPerson(null);
                    bankRepository.save(bank);
                } catch (Exception e) {
                    logger.warn("Could not update relationships for bank ID: {}", bank.getId());
                }
            }

            bankRepository.deleteAll();
            bankRepository.flush();

            logger.info("Force deletion completed");

        } catch (Exception e) {
            logger.error("Error in force deletion: ", e);
            throw new RuntimeException("Failed to force clear bank records: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Bank requestLoan(Long personId, double loanAmount) {
        if (personId == null) {
            logger.error("Invalid Person ID provided for loan request");
            throw new IllegalArgumentException("Person ID cannot be null");
        }
        if (loanAmount <= 0) {
            logger.error("Invalid loan amount: {}", loanAmount);
            throw new IllegalArgumentException("Loan amount must be positive");
        }

        Bank bank = bankRepository.findByPersonId(personId);
        if (bank == null) {
            logger.error("No bank account found for Person ID: {}", personId);
            throw new RuntimeException("Bank account not found for Person ID: " + personId);
        }

        try {
            bank.requestLoan(loanAmount);
            logger.info("Loan request processed for Person ID: {} amount: {}", personId, loanAmount);
            return bankRepository.save(bank);
        } catch (Exception e) {
            logger.error("Error processing loan request", e);
            throw new RuntimeException("Failed to process loan request", e);
        }
    }

    @Transactional
    public Bank repayLoan(Long personId, double repaymentAmount) {
        if (personId == null) {
            logger.error("Invalid Person ID provided for loan repayment");
            throw new IllegalArgumentException("Person ID cannot be null");
        }
        if (repaymentAmount <= 0) {
            logger.error("Invalid repayment amount: {}", repaymentAmount);
            throw new IllegalArgumentException("Repayment amount must be positive");
        }

        Bank bank = bankRepository.findByPersonId(personId);
        if (bank == null) {
            logger.error("No bank account found for Person ID: {}", personId);
            throw new RuntimeException("Bank account not found for Person ID: " + personId);
        }

        try {
            bank.repayLoan(repaymentAmount);
            logger.info("Loan repayment processed for Person ID: {} amount: {}", personId, repaymentAmount);
            return bankRepository.save(bank);
        } catch (Exception e) {
            logger.error("Error processing loan repayment", e);
            throw new RuntimeException("Failed to process loan repayment: " + e.getMessage(), e);
        }
    }
}
