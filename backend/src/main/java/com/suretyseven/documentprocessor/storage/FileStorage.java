package com.suretyseven.documentprocessor.storage;

/**
 * Storage abstraction so the backing implementation (local disk, S3, DB) can be swapped
 * without touching business logic. Only {@link LocalDiskFileStorage} exists for now.
 */
public interface FileStorage {

    /**
     * Writes the given bytes, named by content hash, and returns the path/key they were
     * stored under (opaque to callers — treat it as an identifier to pass back to {@link #read}).
     */
    String write(String contentHash, byte[] bytes, String fileExtension);

    byte[] read(String path);
}
