package com.nekohit.neo.fasusa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger("Application");

    public static void main(String[] args) {
        try {
            if (Env.DEBUG_MODE) {
                logger.info("DEBUG mode enabled");
            }
            long start = System.currentTimeMillis();

            new TxScanner().scan();

            long end = System.currentTimeMillis();
            logger.info("Done. Consumed {} ms", end - start);
        } catch (Throwable t) {
            logger.error("Uncaught throwable", t);
            throw new RuntimeException(t);
        }
    }
}
