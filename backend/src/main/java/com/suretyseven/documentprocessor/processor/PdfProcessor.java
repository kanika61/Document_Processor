package com.suretyseven.documentprocessor.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock PDF extraction. The outcome (SUCCESS/TIMEOUT/ERROR/INVALID_RESULT) is randomized on
 * every call to simulate a flaky external processor. When the outcome is SUCCESS, the
 * extracted fields are derived deterministically from the file's own bytes (not from the
 * outcome roll or attempt number) so that re-processing the same file always yields the same
 * extraction — this is what makes the retryability rule in the plan hold: a VALIDATION_FAILED
 * or INVALID_RESULT failure is reproducible, while TIMEOUT/ERROR are attempt-flakiness that a
 * fresh attempt can clear.
 */
@Component
public class PdfProcessor implements DocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PdfProcessor.class);

    @Override
    public ProcessResult process(byte[] fileBytes, String documentId) {
        ProcessOutcome outcome = randomOutcome();
        log.debug("PdfProcessor simulated outcome={} documentId={}", outcome, documentId);
        if (outcome != ProcessOutcome.SUCCESS) {
            return ProcessResult.of(outcome);
        }
        return ProcessResult.success(extractFinancialStatementFields(fileBytes));
    }

    private ProcessOutcome randomOutcome() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 50) {
            return ProcessOutcome.SUCCESS;
        } else if (roll < 70) {
            return ProcessOutcome.TIMEOUT;
        } else if (roll < 85) {
            return ProcessOutcome.ERROR;
        } else {
            return ProcessOutcome.INVALID_RESULT;
        }
    }

    private Map<String, Object> extractFinancialStatementFields(byte[] fileBytes) {
        long seed = seedFrom(fileBytes);
        Random rnd = new Random(seed);
        Map<String, Object> data = new LinkedHashMap<>();

        // Deterministically simulate a missing companyName for ~1 in 6 files, so
        // VALIDATION_FAILED is actually reachable in testing.
        if (rnd.nextInt(6) != 0) {
            data.put("companyName", "Company-" + Math.abs(seed % 100_000));
        }

        data.put("registrationNumber", "REG-" + Math.abs((seed / 7) % 1_000_000));
        data.put("address", "123 Simulated St, Unit " + (Math.abs(seed % 500) + 1));

        long revenue = Math.abs(seed % 5_000_000);
        if (rnd.nextInt(5) == 0) {
            // Deterministically simulate an invalid negative annualRevenue for ~1 in 5 files.
            revenue = -revenue - 1;
        }
        data.put("annualRevenue", revenue);

        if (rnd.nextInt(6) == 0) {
            // Deterministically simulate an unparseable documentDate for ~1 in 6 files.
            data.put("documentDate", "not-a-date");
        } else {
            long epochDay = Math.floorMod(seed, 15_000);
            data.put("documentDate", LocalDate.ofEpochDay(epochDay).toString());
        }

        return data;
    }

    private long seedFrom(byte[] fileBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(fileBytes);
            long seed = 0;
            for (int i = 0; i < 8; i++) {
                seed = (seed << 8) | (hash[i] & 0xFF);
            }
            return seed;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
