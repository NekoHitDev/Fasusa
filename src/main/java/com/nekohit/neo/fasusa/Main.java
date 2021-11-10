package com.nekohit.neo.fasusa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger("Application");

    public static void main(String[] args) {
        try {
            if (Env.DEBUG_MODE) {
                logger.info("DEBUG mode enabled");
            }
            long start = System.currentTimeMillis();

            TxScanner scanner = new TxScanner();
            if (Env.PROCESS_TX_ONESHOT.isBlank()) {
                logger.info("Start scanning...");
                scanner.scan();
            } else {
                logger.info("Processing tx: {}", Env.PROCESS_TX_ONESHOT);
                scanner.processTxOverride(Env.PROCESS_TX_ONESHOT);
            }
            logger.info("Done, waiting scanner shutdown...");
            scanner.close();

            long end = System.currentTimeMillis();
            logger.info("Exit. Consumed {} ms", end - start);
        } catch (Throwable t) {
            logger.error("Uncaught throwable", t);
            throw new RuntimeException(t);
        }
    }
}
