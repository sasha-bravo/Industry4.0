package org.example;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.iot.raspberry.grovepi.GrovePi;
import org.iot.raspberry.grovepi.pi4j.GrovePi4J;
import org.iot.raspberry.grovepi.sensors.analog.GroveRotarySensor;
import org.iot.raspberry.grovepi.sensors.data.GroveRotaryValue;
import org.iot.raspberry.grovepi.sensors.digital.GroveButton;
import org.iot.raspberry.grovepi.sensors.digital.GroveUltrasonicRanger;
import org.iot.raspberry.grovepi.sensors.listener.GroveButtonListener;
import org.iot.raspberry.grovepi.sensors.synch.SensorMonitor;

public class Linea {

    private static final BatchManager batchManager = new BatchManager();
    private static boolean acquisitionOn = true;

    // Conteggio prodotti
    private static int totalOk = 0;
    private static int totalContaminated = 0;
    private static boolean inZonaScarico = false;

    // Warning stasi smistatore (tuo originale)
    private static Instant lastMovementTime = Instant.now();
    private static double lastKnownAngle = -1.0;

    // Timer lavorazione batch
    private static Instant batchLavorazioneStart = null;  // parte col pulsante
    private static boolean batchTimerRunning = false;
    private static boolean containerScaricato = false;

    // Soglie botole — CALIBRARE con le stampe di debug
    private static final double SOGLIA_APERTURA_DX = 115.0; // aperta se maggiore di 115
    private static final double SOGLIA_APERTURA_SX = 193.0; // aperta se minore di 193

    // Buffer latency — tempo da smistatore a botola
    private static Instant smistatoreDxOpenTime = null; // quando smistatore apre verso DX
    private static Instant smistatoreSxOpenTime = null; // quando smistatore apre verso SX
    private static boolean smistatoreDxAperto = false;
    private static boolean smistatoreSxAperto = false;
    private static boolean botolaDxAperta = false;
    private static boolean botolaSxAperta = false;

    // Soglia ranger per container scaricato (cm) — calibra in base alla tua linea
    private static final double DIST_CONTAINER_SCARICATO = 10;

    // warning stasi
    private static boolean warningStasiAttivo = false;

    private static Instant lastBotolaDxSeenTime = null;

    public static void main(String[] args) throws Exception {
        Logger.getLogger("").setLevel(Level.OFF);
        Logger.getLogger("RaspberryPi").setLevel(Level.WARNING);

        GrovePi grovePi = new GrovePi4J();
        String token = "lGWboQYsVDkSZOPOZNmVgjQ_qNlFC0JLRJdnC_DicfNaKbPXe0IGPGTkpUaaLlVrnPy65ssX0UukfOvIGKEu1g==";
        String bucket = "Linea";
        String org = "SUPSI";

        InfluxDBClient client = InfluxDBClientFactory.create("http://169.254.187.21:8086", token.toCharArray());

        GroveUltrasonicRanger ranger = new GroveUltrasonicRanger(grovePi, 4);
        SensorMonitor<Double> monitor_ranger = new SensorMonitor<>(ranger, 500L);

        GroveRotarySensor smistatore = new GroveRotarySensor(grovePi, 0);
        SensorMonitor<GroveRotaryValue> monitor_rotor = new SensorMonitor<>(smistatore, 50L);

        GroveRotarySensor botola_dx = new GroveRotarySensor(grovePi, 1);
        SensorMonitor<GroveRotaryValue> monitor_rotor_dx = new SensorMonitor<>(botola_dx, 50L);

        GroveRotarySensor botola_sx = new GroveRotarySensor(grovePi, 2);
        SensorMonitor<GroveRotaryValue> monitor_rotor_sx = new SensorMonitor<>(botola_sx, 50L);

        GroveButton onOffButton = new GroveButton(grovePi, 3);
        SensorMonitor<Boolean> onOffButtonMonitor = new SensorMonitor<>(onOffButton, 50L);

        // Click pulsante: avvia timer lavorazione batch
        GroveButtonListener buttonListener = new GroveButtonListener() {
            public void onRelease() {
            }

            public void onPress() {
            }

            public void onClick() {
                if (!batchTimerRunning) {
                    batchLavorazioneStart = Instant.now();
                    batchTimerRunning = true;
                    containerScaricato = false;
                    System.out.println("[BATCH] Timer lavorazione avviato: " + batchLavorazioneStart);
                } else {
                    System.out.println("[BATCH] Timer già in esecuzione, ignoro click.");
                }
            }
        };

        onOffButton.setButtonListener(buttonListener);
        onOffButtonMonitor.start();
        monitor_rotor.start();
        monitor_ranger.start();
        monitor_rotor_dx.start();
        monitor_rotor_sx.start();

        WriteApiBlocking writeApi = client.getWriteApiBlocking();

        while (true) {
            if (acquisitionOn
                    && monitor_rotor.isValid()
                    && monitor_rotor_dx.isValid()
                    && monitor_rotor_sx.isValid()) {

                GroveRotaryValue smistatoreDegree = (GroveRotaryValue) monitor_rotor.getValue();
                GroveRotaryValue botoladxDegree = (GroveRotaryValue) monitor_rotor_dx.getValue();
                GroveRotaryValue botolasxDegree = (GroveRotaryValue) monitor_rotor_sx.getValue();

                double currentAngle = smistatoreDegree.getDegrees();
                double currentDx = botoladxDegree.getDegrees();
                double currentSx = botolasxDegree.getDegrees();



                BallItem ball = null;

                // --- Logica conteggio e rilevamento palline ---
                if (currentAngle < 170.0) {
                    if (!inZonaScarico) {
                        totalOk++;
                        inZonaScarico = true;
                        ball = new BallItem("OK");
                        batchManager.addBall(ball);

                        //smistatore apre verso sinistra -> avvia timer buffer sx
                        smistatoreSxOpenTime = Instant.now();
                        smistatoreSxAperto = true;
                        System.out.println("[INFO] Farmaco CONFORME rilevato. Totale OK: " + totalOk);
                    }
                } else if (currentAngle > 200.0) {
                    if (!inZonaScarico) {
                        totalContaminated++;
                        inZonaScarico = true;
                        ball = new BallItem("CONTAMINATO");
                        ball.addWarning("CONTAMINATO", "angle=" + currentAngle);
                        batchManager.addBall(ball);
                        //smistatore apre verso destra -> avvia timer buffer dx
                        smistatoreDxOpenTime = Instant.now();
                        smistatoreDxAperto = true;
                        System.err.println("[INFO] Farmaco CONTAMINATO rilevato! Totale Scarti: " + totalContaminated);
                    }
                } else {
                    inZonaScarico = false;
                }

                // --- Warning stasi smistatore (tuo originale) ---
                if (Math.abs(currentAngle - lastKnownAngle) > 3.0) {
                    lastMovementTime = Instant.now();
                    lastKnownAngle = currentAngle;
                    warningStasiAttivo = false; // reset: smistatore si è rimesso in moto
                } else {
                    long secondiFermi = Duration.between(lastMovementTime, Instant.now()).getSeconds();
                    if (secondiFermi > 10 && !warningStasiAttivo) {
                        warningStasiAttivo = true; // blocca print successivi
                        System.err.println("[WARNING] SMISTATORE FERMO, POSSIBILE INTASAMENTO");
                        Point warningPoint = Point.measurement("warning")
                                .addTag("uuid_batch", batchManager.getCurrentBatchUuid())
                                .addField("message", "SMISTATORE_FERMO: secondi_fermi=" + secondiFermi)
                                .time(Instant.now(), WritePrecision.NS);
                        writeApi.writePoint(bucket, org, warningPoint);
                    }

                }
                // --- Invio dati principali su InfluxDB (solo se pallina rilevata) ---
                if (ball != null) {

                    Point farmacoPoint = Point.measurement("farmaco")
                            .addTag("stato", ball.routedTo)
                            .addTag("uuid_farmaco", ball.ballUuid)
                            .addField("count", 1L)
                            .time(Instant.now(), WritePrecision.NS);
                    writeApi.writePoint(bucket, org, farmacoPoint);

                    Point sorterPoint = Point.measurement("sorter_angle")
                            .addField("sorting_angle", currentAngle)
                            .time(Instant.now(), WritePrecision.NS);
                    writeApi.writePoint(bucket, org, sorterPoint);
                }

                // 4. measurement grezzo botole
                try {
                    Point botolaDxPoint = Point.measurement("botola_dx")
                            .addField("angle", currentDx)
                            .time(Instant.now(), WritePrecision.NS);
                    writeApi.writePoint(bucket, org, botolaDxPoint);

                    Point botolaSxPoint = Point.measurement("botola_sx")
                            .addField("angle", currentSx)
                            .time(Instant.now(), WritePrecision.NS);
                    writeApi.writePoint(bucket, org, botolaSxPoint);
                } catch (Exception e) {
                    System.err.println("[INFLUX ERROR botole] " + e.getMessage());
                }

                // 5. buffer latency DX
                // smistatore aveva aperto verso DX, aspetto che botola_dx si apra
                if (smistatoreDxAperto && !botolaDxAperta) {
                    if (currentDx > SOGLIA_APERTURA_DX) {
                        // botola DX si è aperta → calcola latenza
                        botolaDxAperta = true;
                        long latenzaMs = Duration.between(smistatoreDxOpenTime, Instant.now()).toMillis();
                        System.out.println("[BUFFER DX] latenza=" + latenzaMs + "ms");
                        try {
                            Point bufferPoint = Point.measurement("buffer_latency")
                                    .addTag("botola", "dx")
                                    .addTag("uuid_batch", batchManager.getCurrentBatchUuid())
                                    .addField("latenza_ms", latenzaMs)
                                    .time(Instant.now(), WritePrecision.NS);
                            writeApi.writePoint(bucket, org, bufferPoint);
                        } catch (Exception e) {
                            System.err.println("[INFLUX ERROR buffer_dx] " + e.getMessage());
                        }
                    }
                }
                // reset quando botola DX si richiude
                if (botolaDxAperta && currentDx <= SOGLIA_APERTURA_DX) {
                    botolaDxAperta     = false;
                    smistatoreDxAperto = false;
                }

                // 6. buffer latency SX
                if (smistatoreSxAperto && !botolaSxAperta) {
                    if (currentSx < SOGLIA_APERTURA_SX) {
                        // botola SX si è aperta → calcola latenza
                        botolaSxAperta = true;
                        long latenzaMs = Duration.between(smistatoreSxOpenTime, Instant.now()).toMillis();
                        System.out.println("[BUFFER SX] latenza=" + latenzaMs + "ms");
                        try {
                            Point bufferPoint = Point.measurement("buffer_latency")
                                    .addTag("botola", "sx")
                                    .addTag("uuid_batch", batchManager.getCurrentBatchUuid())
                                    .addField("latenza_ms", latenzaMs)
                                    .time(Instant.now(), WritePrecision.NS);
                            writeApi.writePoint(bucket, org, bufferPoint);
                        } catch (Exception e) {
                            System.err.println("[INFLUX ERROR buffer_sx] " + e.getMessage());
                        }
                    }
                }
                // reset quando botola SX si richiude
                if (botolaSxAperta && currentSx >= SOGLIA_APERTURA_SX) {
                    botolaSxAperta     = false;
                    smistatoreSxAperto = false;
                }



                // --- Rilevamento fine batch tramite ranger (container scaricato) ---
                if (monitor_ranger.isValid()) {
                    double distanza = monitor_ranger.getValue();

                    // ignora letture anomale
                    if (distanza <= 0 || distanza > 300) {
                        continue;
                    }
                    boolean present = distanza <= DIST_CONTAINER_SCARICATO;


                    Point rangerPoint = Point.measurement("ranger")
                            .addTag("present", String.valueOf(present))
                            .addField("distance", distanza)
                            .time(Instant.now(), WritePrecision.NS);
                    writeApi.writePoint(bucket, org, rangerPoint);
                    if (batchTimerRunning && !containerScaricato && !present) {

                        int totPalline = totalOk + totalContaminated;
                        if (totPalline < BatchManager.BATCH_SIZE) {
                            System.err.println("[WARNING] Batch incompleto: " + totPalline + "/" + BatchManager.BATCH_SIZE + " palline");
                            try {
                                Point warningPoint = Point.measurement("warning")
                                        .addTag("uuid_batch", batchManager.getCurrentBatchUuid())
                                        .addField("message", "BATCH_INCOMPLETO: " + totPalline + "/" + BatchManager.BATCH_SIZE)
                                        .time(Instant.now(), WritePrecision.NS);
                                writeApi.writePoint(bucket, org, warningPoint);
                            } catch (Exception e) {
                                System.err.println("[INFLUX ERROR warning] " + e.getMessage());
                            }
                        }

                        containerScaricato = true;
                        Instant fineScarico = Instant.now();
                        long durataBatch = Duration.between(batchLavorazioneStart, fineScarico).getSeconds();
                        batchTimerRunning = false;

                        double qualita = totalOk + totalContaminated > 0
                                ? (double) totalOk / (totalOk + totalContaminated) * 100
                                : 0.0;

                        try {
                            Point batchPoint = Point.measurement("batch")
                                    .addField("qualita", String.format("%.1f", qualita))
                                    .addTag("uuid_batch",              batchManager.getCurrentBatchUuid())
                                    .addField("cnt_palline_ok",          (long) totalOk)
                                    .addField("cnt_palline_contaminate", (long) totalContaminated)
                                    .addField("tempo_esecuzione",        durataBatch)
                                    .time(fineScarico, WritePrecision.NS);
                            writeApi.writePoint(bucket, org, batchPoint);
                            System.out.println("[BATCH] Point scritto. Qualità: " + qualita + "%");

                            // chiudi il batch → resetta UUID e stato interno
                            batchManager.closeBatch(durataBatch);

                        } catch (Exception e) {
                            System.err.println("[INFLUX ERROR batch] " + e.getMessage());
                        }

                        totalOk = 0;
                        totalContaminated = 0;

                    }


                }
            }
        }
    }
}