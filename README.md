# Relazione Tecnica — Progetto Industry 4.0
## Linea Automatizzata di Smistamento e Controllo Qualità Farmaci

**Corso:** Industry 4.0  
**Istituzione:** SUPSI  
**Linguaggio:** Java 8  
**Hardware:** Raspberry Pi + GrovePi  
**Database:** InfluxDB 2.x  

---

## 1. Introduzione

Il progetto realizza un sistema di **controllo qualità automatizzato per una linea di produzione farmaceutica**, simulata fisicamente con palline. Il sistema è in grado di:

- rilevare e classificare i prodotti in conformi o contaminati
- raggruppare i prodotti in lotti (batch) e calcolarne la qualità
- misurare la latenza meccanica del processo di smistamento
- rilevare anomalie operative (stasi, ritardi, batch incompleti)
- inviare tutti i dati di telemetria in tempo reale a un database InfluxDB

Il software è sviluppato in **Java 8** con Maven, viene eseguito su un **Raspberry Pi** equipaggiato con una scheda di espansione **GrovePi**, e si integra con una dashboard di monitoraggio tramite **InfluxDB**.

---

## 2. Architettura del Sistema

### 2.1 Componenti Hardware

I sensori sono collegati alla scheda GrovePi montata sul Raspberry Pi:

| Sensore | Tipo pin | Pin | Ruolo |
|---|---|---|---|
| `GroveUltrasonicRanger` | Digitale | D4 | Rileva se il container è vuoto o pieno |
| `GroveRotarySensor` (smistatore) | Analogico | A0 | Misura l'angolo dello smistatore per classificare la pallina |
| `GroveRotarySensor` (botola destra) | Analogico | A1 | Rileva l'apertura della botola per farmaci conformi |
| `GroveRotarySensor` (botola sinistra) | Analogico | A2 | Rileva l'apertura della botola per farmaci contaminati |
| `GroveButton` | Digitale | D3 | Avvia manualmente il timer di lavorazione del batch |

### 2.2 Struttura del Software

```
Progetto_Finale/
├── pom.xml
└── src/main/java/org/example/
    ├── BallItem.java       # Modello dati di una singola pallina
    ├── BatchManager.java   # Gestione dei lotti di produzione
    ├── Linea.java          # Entry point — loop di acquisizione principale
    └── Test_ranger.java    # Utility di calibrazione (non produzione)
```

### 2.3 Relazioni tra le classi

`Linea` è la classe principale: contiene il metodo `main` e orchestra tutto il sistema. Ad ogni pallina rilevata crea un oggetto `BallItem`, che rappresenta quel singolo prodotto con il suo stato, il suo UUID e gli eventuali warning. `Linea` passa ogni `BallItem` al `BatchManager`, che si occupa di accumularli in lotti da 10, rilevare ritardi tra una pallina e l'altra, e chiudere il batch al momento opportuno. `Test_ranger` è invece un programma del tutto indipendente, usato solo in fase di calibrazione dell'hardware.

---

## 3. Analisi del Codice

### 3.1 `BallItem.java` — Modello di una Pallina

Ogni pallina che transita sulla linea viene istanziata come oggetto `BallItem`. Gli attributi principali sono:

```java
public final String       ballUuid;        // ID univoco (UUID v4)
public final Instant      timestamp;       // istante di rilevazione
public       String       routedTo;        // "OK" oppure "CONTAMINATO"
public final List<String> warnings;        // lista di anomalie rilevate
```

Una funzionalità chiave è la **misurazione della latenza meccanica**: il sistema registra l'istante in cui lo smistatore si apre e calcola quanto tempo passa prima che la botola fisica risponda.

```java
// Chiamato quando lo smistatore si orienta verso una botola
public void setSmistatoreOpenTime(Instant time) {
    this.smistatoreOpenTime = time;
}

// Chiamato quando la botola fisica si apre — calcola il ritardo
public void calcolaLatenza() {
    if (smistatoreOpenTime != null && latenzaMs == null) {
        this.latenzaMs = Duration.between(smistatoreOpenTime, Instant.now()).toMillis();
    }
}
```

Questo permette di monitorare nel tempo l'efficienza meccanica della linea.

---

### 3.2 `BatchManager.java` — Gestione dei Lotti

Le palline vengono raggruppate in **lotti da 10 unità**. La costante `BATCH_SIZE` è configurabile:

```java
public static final int  BATCH_SIZE                = 10;
public static final long MAX_SECONDS_BETWEEN_BALLS = 15;
```

Ad ogni pallina aggiunta, il manager verifica se è trascorso troppo tempo dall'ultima:

```java
if (lastBallTime != null) {
    long ritardo = Duration.between(lastBallTime, Instant.now()).getSeconds();
    if (ritardo > MAX_SECONDS_BETWEEN_BALLS) {
        ball.addWarning("RITARDO_PALLINA", "attesa " + ritardo + "s dall'ultima");
    }
}
```

Al termine del lotto, il metodo `closeBatch()` stampa un riepilogo e **resetta automaticamente** lo stato interno (UUID, contatori, timer) per il batch successivo.

---

### 3.3 `Linea.java` — Logica Principale

Questa è la classe centrale del sistema. Il metodo `main` inizializza tutti i sensori con i rispettivi monitor asincroni e avvia un loop continuo a **50 Hz (ogni 20 ms)**:

```java
Thread.sleep(20); // frequenza di campionamento: 50 Hz
```

#### Inizializzazione sensori

```java
GrovePi grovePi = new GrovePi4J();

GroveUltrasonicRanger ranger     = new GroveUltrasonicRanger(grovePi, 4);
GroveRotarySensor smistatore     = new GroveRotarySensor(grovePi, 0);
GroveRotarySensor botola_dx      = new GroveRotarySensor(grovePi, 1);
GroveRotarySensor botola_sx      = new GroveRotarySensor(grovePi, 2);
GroveButton onOffButton          = new GroveButton(grovePi, 3);
```

Ogni sensore viene avvolto in un `SensorMonitor` che legge il valore in un thread separato, rendendo la lettura non bloccante.

#### Classificazione della pallina

La classificazione avviene in base all'angolo letto dal sensore rotazionale dello smistatore. Vengono definite due **zone di scarico** con isteresi, per evitare doppi conteggi:

```java
if (currentAngle < 170.0) {
    if (!inZonaScaricoDx) {
        // nuova pallina conforme rilevata
        totalOk++;
        inZonaScaricoDx = true;
        ball = new BallItem("OK");
        ball.setSmistatoreOpenTime(Instant.now());
        pendingBallDx = ball;
        batchManager.addBall(ball);
    }
} else if (currentAngle > 200.0) {
    if (!inZonaScaricoSx) {
        // nuova pallina contaminata rilevata
        totalContaminated++;
        inZonaScaricoSx = true;
        ball = new BallItem("CONTAMINATO");
        ball.addWarning("CONTAMINATO", "angle=" + currentAngle);
        // ...
    }
} else {
    // zona neutra: reset dei flag per il prossimo rilevamento
    inZonaScaricoDx = false;
    inZonaScaricoSx = false;
}
```

Le soglie angolari (`< 170°` e `> 200°`) sono calibrate fisicamente sulla linea reale.

#### Rilevamento stasi smistatore

Il sistema monitora continuamente il movimento dello smistatore. Se rimane fermo per più di 10 secondi, viene generato un warning sia in console sia su InfluxDB:

```java
if (Math.abs(currentAngle - lastKnownAngle) > 3.0) {
    lastMovementTime = Instant.now();
    lastKnownAngle   = currentAngle;
    warningStasiAttivo = false; // si è rimesso in moto
} else {
    long secondiFermi = Duration.between(lastMovementTime, Instant.now()).getSeconds();
    if (secondiFermi > 10 && !warningStasiAttivo) {
        warningStasiAttivo = true;
        // scrittura warning su InfluxDB + log su stderr
    }
}
```

Il flag `warningStasiAttivo` evita che lo stesso warning venga scritto più volte mentre la macchina è ferma.

#### Chiusura del batch e calcolo qualità

Il ranger ultrasonico rileva quando il container si svuota. In quel momento, se il timer batch è attivo, il sistema:
1. Verifica la completezza del lotto
2. Calcola la qualità percentuale
3. Scrive il record su InfluxDB
4. Resetta i contatori

```java
double qualita = (totalOk + totalContaminated) > 0
        ? (double) totalOk / (totalOk + totalContaminated) * 100
        : 0.0;

Point batchPoint = Point.measurement("batch")
        .addField("qualita",                  String.format("%.1f", qualita))
        .addField("cnt_palline_ok",           (long) totalOk)
        .addField("cnt_palline_contaminate",  (long) totalContaminated)
        .addField("tempo_esecuzione",         durataBatch)
        .addTag("uuid_batch",                 batchManager.getCurrentBatchUuid())
        .time(fineScarico, WritePrecision.NS);
writeApi.writePoint(bucket, org, batchPoint);
```

---

### 3.4 `Test_ranger.java` — Calibrazione

Programma standalone usato in fase di setup per verificare il funzionamento del sensore ultrasonico e determinare la soglia di rilevamento corretta. Stampa ogni 500 ms la distanza rilevata:

```java
System.out.printf("[RANGER] distanza=%.1fcm | present=%-5s | %s%n",
        distanza,
        present,
        present ? "BOTOLA PRESENTE ✓" : "BOTOLA ASSENTE  ✗");
```

Non viene usato in produzione.

---

## 4. Flusso Dati verso InfluxDB

- **URL**: `http://169.254.187.21:8086`  
- **Bucket**: `Linea`  
- **Organizzazione**: `SUPSI`

| Measurement | Tag principali | Campi | Frequenza di scrittura |
|---|---|---|---|
| `farmaco` | `stato`, `uuid_farmaco` | `count` | Ad ogni pallina rilevata |
| `sorter_angle` | — | `sorting_angle` | Ad ogni pallina rilevata |
| `botola_dx` | — | `angle` | Ogni ciclo (20 ms) |
| `botola_sx` | — | `angle` | Ogni ciclo (20 ms) |
| `buffer_latency` | `botola`, `uuid_farmaco`, `uuid_batch` | `latenza_ms` | All'apertura della botola |
| `batch` | `uuid_batch` | `qualita`, `cnt_palline_ok`, `cnt_palline_contaminate`, `tempo_esecuzione` | Fine batch |
| `warning` | `uuid_batch` | `message` | Solo in caso di anomalia |

---

## 5. Sistema di Warning e Qualità

Il sistema gestisce quattro tipi di anomalia:

| Warning | Condizione di attivazione | Dove viene registrato |
|---|---|---|
| `CONTAMINATO` | Angolo smistatore > 200° | InfluxDB (`farmaco`) + console |
| `RITARDO_PALLINA` | Pausa > 15 s tra palline consecutive | Sull'oggetto `BallItem` |
| `SMISTATORE_FERMO` | Angolo invariato per > 10 s | InfluxDB (`warning`) + console |
| `BATCH_INCOMPLETO` | Container svuotato con < 10 palline | InfluxDB (`warning`) + console |

---

## 6. Dipendenze del Progetto

Definite nel file `pom.xml` (build con Maven):

| Libreria | Versione | Scopo |
|---|---|---|
| `GrovePi-pi4j` | 0.1.0-SNAPSHOT | Interfaccia Java con i sensori fisici via GPIO del Raspberry Pi |
| `influxdb-client-java` | 6.6.0 | Client ufficiale InfluxDB per la scrittura dei dati di telemetria |

Il repository Maven di `GrovePi-pi4j` è ospitato sul GitLab interno SUPSI:
```
https://gitlab-edu.supsi.ch/api/v4/groups/93/-/packages/maven
```

---

## 7. Requisiti di Esecuzione

- Raspberry Pi con scheda GrovePi collegata
- JDK 8 o superiore + Maven
- Istanza InfluxDB raggiungibile all'indirizzo configurato
- Sensori fisici collegati ai pin indicati nella sezione 2.1

> **Nota:** il programma non è eseguibile senza l'hardware fisico connesso. Prima dell'avvio in produzione, si consiglia di eseguire `Test_ranger.java` per verificare il corretto funzionamento del sensore di distanza e calibrare la soglia di rilevamento del container.

---

## 8. Conclusioni

Il sistema implementa in modo efficace una linea di controllo qualità industriale su hardware embedded a basso costo. I punti di forza sono:

- **Tracciabilità completa**: ogni pallina ha un UUID univoco, ogni lotto è identificato separatamente
- **Monitoraggio in tempo reale**: tutti i dati vengono scritti su InfluxDB con timestamp a nanosecondo
- **Rilevamento proattivo delle anomalie**: quattro tipi di warning coprono i principali scenari di guasto
- **Misura della latenza meccanica**: permette di individuare degradazioni fisiche nel tempo

Un aspetto migliorabile è la gestione del token di autenticazione InfluxDB, attualmente hardcodato nel sorgente: in un contesto di produzione andrebbe esternalizzato tramite variabili d'ambiente o file di configurazione.

