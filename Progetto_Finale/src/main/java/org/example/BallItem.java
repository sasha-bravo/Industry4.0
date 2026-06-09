package org.example;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BallItem {

    public final String      ballUuid;
    public final Instant     timestamp;
    public       int         positionInBatch;
    public       String      routedTo;
    public final List<String> warnings = new ArrayList<>();

    public BallItem(String routedTo) {
        this.ballUuid  = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.routedTo  = routedTo;
    }

    public void addWarning(String type, String detail) {
        warnings.add(type + ": " + detail);
        System.err.println("[RILEVATO][" + ballUuid.substring(0, 8) + "] " + type + " - " + detail);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}