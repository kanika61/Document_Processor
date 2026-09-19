package com.suretyseven.documentprocessor.storage;

import com.suretyseven.documentprocessor.config.DocumentProcessingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class LocalDiskFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskFileStorage.class);

    private final Path basePath;

    public LocalDiskFileStorage(DocumentProcessingProperties properties) {
        this.basePath = Path.of(properties.getStorage().getBasePath());
        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            throw new FileStorageException("Unable to initialize storage directory: " + basePath, e);
        }
    }

    @Override
    public String write(String contentHash, byte[] bytes, String fileExtension) {
        Path target = basePath.resolve(contentHash + fileExtension);
        try {
            Files.write(target, bytes);
        } catch (IOException e) {
            log.error("Failed to write file to disk at {}", target, e);
            throw new FileStorageException("Failed to write file to disk", e);
        }
        return target.toString();
    }

    @Override
    public byte[] read(String path) {
        try {
            return Files.readAllBytes(Path.of(path));
        } catch (IOException e) {
            log.error("Failed to read file from disk at {}", path, e);
            throw new FileStorageException("Failed to read file from disk", e);
        }
    }
}
