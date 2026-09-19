package com.suretyseven.documentprocessor.storage;

import com.suretyseven.documentprocessor.config.DocumentProcessingProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDiskFileStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void writeThenRead_roundTripsBytes() {
        DocumentProcessingProperties properties = new DocumentProcessingProperties();
        properties.getStorage().setBasePath(tempDir.resolve("docs").toString());
        LocalDiskFileStorage storage = new LocalDiskFileStorage(properties);

        byte[] original = "hello world".getBytes();
        String path = storage.write("abc123", original, ".pdf");

        assertThat(path).endsWith("abc123.pdf");
        assertThat(storage.read(path)).isEqualTo(original);
    }
}
