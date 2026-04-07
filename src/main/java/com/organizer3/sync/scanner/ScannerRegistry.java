package com.organizer3.sync.scanner;

import java.util.Map;

/**
 * Maps structure type IDs to their corresponding {@link VolumeScanner} implementations.
 *
 * <p>Constructed once at startup and shared by all sync operations that need
 * structure-aware scanning.
 */
public class ScannerRegistry {

    private final Map<String, VolumeScanner> scanners;

    public ScannerRegistry(Map<String, VolumeScanner> scanners) {
        this.scanners = Map.copyOf(scanners);
    }

    /**
     * Returns the scanner for the given structure type.
     *
     * @throws IllegalArgumentException if no scanner is registered for the type
     */
    public VolumeScanner forStructureType(String structureType) {
        VolumeScanner scanner = scanners.get(structureType);
        if (scanner == null) {
            throw new IllegalArgumentException("No scanner registered for structure type: " + structureType);
        }
        return scanner;
    }
}
