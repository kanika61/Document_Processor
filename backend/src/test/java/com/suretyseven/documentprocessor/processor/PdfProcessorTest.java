package com.suretyseven.documentprocessor.processor;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfProcessorTest {

    private final PdfProcessor processor = new PdfProcessor();

    @RepeatedTest(20)
    void successfulExtraction_isDeterministicForTheSameFileBytes() {
        byte[] bytes = "some pdf content".getBytes();

        // Call repeatedly until we observe two SUCCESS outcomes (outcome is random per call),
        // then assert the extracted fields are byte-for-byte identical both times — this is
        // the invariant the retryability rule in the plan depends on.
        ProcessResult first = firstSuccess(bytes);
        ProcessResult second = firstSuccess(bytes);

        assertThat(second.extractedData()).isEqualTo(first.extractedData());
    }

    @Test
    void differentFileBytes_canProduceDifferentExtraction() {
        ProcessResult a = firstSuccess("file-a".getBytes());
        ProcessResult b = firstSuccess("file-b-totally-different".getBytes());

        // Not a strict guarantee for every possible byte pair, but overwhelmingly true in
        // practice since extraction is seeded from a SHA-256 hash of the bytes.
        assertThat(a.extractedData()).isNotEqualTo(b.extractedData());
    }

    private ProcessResult firstSuccess(byte[] bytes) {
        for (int i = 0; i < 200; i++) {
            ProcessResult result = processor.process(bytes, "DOC-test");
            if (result.outcome() == ProcessOutcome.SUCCESS) {
                return result;
            }
        }
        throw new AssertionError("Did not observe a SUCCESS outcome in 200 attempts");
    }
}
