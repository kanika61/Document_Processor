package com.suretyseven.documentprocessor.processor;

import java.util.Map;

public record ProcessResult(ProcessOutcome outcome, Map<String, Object> extractedData) {

    public static ProcessResult success(Map<String, Object> extractedData) {
        return new ProcessResult(ProcessOutcome.SUCCESS, extractedData);
    }

    public static ProcessResult of(ProcessOutcome outcome) {
        return new ProcessResult(outcome, null);
    }
}
