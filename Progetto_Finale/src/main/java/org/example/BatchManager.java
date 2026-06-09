package org.example;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BatchManager {

    public static final int  BATCH_SIZE                = 10;
    public static final long MAX_SECONDS_BETWEEN_BALLS = 15;

    private String           batchUuid      = UUID.randomUUID().toString();
    private final List<BallItem> currentBatch = new ArrayList<>();
    private int              batchNumber    = 0;
    private Instant          batchStartTime = null; // parte con la prima pallina
    private Instant          lastBallTime   = null; // null finché non arriva la prima pallina

    public boolean addBall(BallItem ball) {
        ball.positionInBatch = currentBatch.size() + 1;

        // Controlla ritardo dall'ultima pallina
        if (lastBallTime != null) {
            long ritardo = Duration.between(lastBallTime, Instant.now()).getSeconds();
            if (ritardo > MAX_SECONDS_BETWEEN_BALLS) {
                ball.addWarning("RITARDO_PALLINA", "attesa " + ritardo + "s dall'ultima");
            }
        }

        lastBallTime = Instant.now();
        currentBatch.add(ball);

        System.err.printf("[BATCH %s][%d/%d] ball=%s route=%s warnings=%s%n",
                batchUuid.substring(0, 8),
                currentBatch.size(), BATCH_SIZE,
                ball.ballUuid.substring(0, 8),
                ball.routedTo,
                ball.warnings);

        return false;
    }

    public void closeBatch(long durata) {
        batchNumber++;
        long totalWarnings = currentBatch.stream()
                .filter(BallItem::hasWarnings).count();

        System.out.printf("[BATCH CHIUSO #%d] uuid=%s | durata=%ds | warnings=%d%n",
                batchNumber, batchUuid, durata, totalWarnings);

        // Reset per il prossimo batch
        currentBatch.clear();
        batchUuid      = UUID.randomUUID().toString();
        batchStartTime = null;
        lastBallTime   = null;
    }

    public String  getCurrentBatchUuid() { return batchUuid; }
    public int     getCurrentCount()     { return currentBatch.size(); }
    public Instant getLastBallTime()     { return lastBallTime; }
    public Instant getBatchStartTime()   { return batchStartTime; }
}