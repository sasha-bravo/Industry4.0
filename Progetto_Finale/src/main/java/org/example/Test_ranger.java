package org.example;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.iot.raspberry.grovepi.GrovePi;
import org.iot.raspberry.grovepi.pi4j.GrovePi4J;
import org.iot.raspberry.grovepi.sensors.digital.GroveUltrasonicRanger;
import org.iot.raspberry.grovepi.sensors.synch.SensorMonitor;

public class Test_ranger {

    public static void main(String[] args) throws Exception {
        Logger.getLogger("").setLevel(Level.OFF);
        Logger.getLogger("RaspberryPi").setLevel(Level.WARNING);

        GrovePi grovePi = new GrovePi4J();

        GroveUltrasonicRanger ranger = new GroveUltrasonicRanger(grovePi, 4);
        SensorMonitor<Double> monitor_ranger = new SensorMonitor<>(ranger, 500L);
        monitor_ranger.start();

        System.out.println("=== RANGER TEST ===");
        System.out.println("Soglia attuale: 15.0 cm");
        System.out.println("Premi CTRL+C per fermare\n");

        while (true) {
            if (monitor_ranger.isValid()) {
                double distanza = monitor_ranger.getValue();

                if (distanza <= 0 || distanza > 300) {
                    System.out.println("[RANGER] lettura anomala: " + distanza + "cm — ignorata");
                    continue;
                }

                boolean present = distanza <= 15.0;
                System.out.printf("[RANGER] distanza=%.1fcm | present=%-5s | %s%n",
                        distanza,
                        present,
                        present ? "BOTOLA PRESENTE ✓" : "BOTOLA ASSENTE  ✗");
            }
            Thread.sleep(500);
        }
    }
}