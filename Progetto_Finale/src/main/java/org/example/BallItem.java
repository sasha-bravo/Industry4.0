package org.example;

import java.time.Duration;
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
    // latency
    private Instant smistatoreOpenTime = null;
    private Long latenzaMs = null;

    public BallItem(String routedTo) {
        this.ballUuid  = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.routedTo  = routedTo;
    }


    // setters
    // chiamato quando smistatore apre
    public void setSmistatoreOpenTime(Instant time){
        this.smistatoreOpenTime = time;
    }
    // chiamato quando botola si apre
    public void calcolaLatenza()    {
        if (smistatoreOpenTime!=null && latenzaMs == null){
            this.latenzaMs = Duration.between(smistatoreOpenTime, Instant.now()).toMillis();
        }
    }


    // getters
    public Long getLatenzaMs(){
        return latenzaMs;
    }
    public boolean hasLatenza(){
        return latenzaMs != null;
    }

    public void addWarning(String type, String detail) {
        warnings.add(type + ": " + detail);
        System.err.println("[RILEVATO][" + ballUuid.substring(0, 8) + "] " + type + " - " + detail);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}