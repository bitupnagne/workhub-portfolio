package com.portfolio.worksystem.model;

public record SystemStorageStatus(
        String label,
        String path,
        boolean exists,
        boolean directory,
        boolean writable,
        String message,
        String fixCommand
) {
}
