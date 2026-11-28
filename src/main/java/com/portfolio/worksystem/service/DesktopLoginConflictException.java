package com.portfolio.worksystem.service;

public class DesktopLoginConflictException extends RuntimeException {
    private final String existingDevice;

    public DesktopLoginConflictException(String existingDevice) {
        super("Desktop session already exists.");
        this.existingDevice = existingDevice;
    }

    public String getExistingDevice() {
        return existingDevice;
    }
}
