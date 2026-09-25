package com.ai.service.access;

public class AppAccessDeniedException extends RuntimeException {

    public AppAccessDeniedException() {
        super("A valid X-APP-CODE header is required");
    }
}
