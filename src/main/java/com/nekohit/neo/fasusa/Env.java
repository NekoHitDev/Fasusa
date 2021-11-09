package com.nekohit.neo.fasusa;

import io.neow3j.types.Hash160;
import io.neow3j.wallet.Account;
import okhttp3.OkHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.InetSocketAddress;
import java.net.Proxy;

public class Env {
    public static final boolean DEBUG_MODE =
            Boolean.parseBoolean(readFromEnv("DEBUG_MODE", "false"));

    public static final String NODE_URL = readFromEnv("NODE_URL");
    public static final long SCAN_DURATION_MIN = Long.parseLong(readFromEnv("SCAN_DURATION_MIN", "30"));
    public static final String DYNAMODB_TABLE_NAME = readFromEnv("DYNAMODB_TABLE_NAME");
    public static final Hash160 GAS_RECEIVE_ADDRESS = Hash160.fromAddress(readFromEnv("GAS_RECEIVE_ADDRESS"));
    public static final Hash160 CAT_TOKEN_SCRIPT_HASH = new Hash160(readFromEnv("CAT_TOKEN_HASH"));
    public static final Account CAT_ASSET_ACCOUNT = Account.fromWIF(readFromEnv("CAT_ACCOUNT_WIF"));

    public static OkHttpClient getHttpClient() {
        var builder = new OkHttpClient.Builder();
        if (DEBUG_MODE) {
            // A proxy for debug
            builder = builder.proxy(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", 1080)));
        }
        return builder.build();
    }

    public static DynamoDbClient getDynamoDbClient() {
        var builder = DynamoDbClient.builder();
        if (DEBUG_MODE) {
            // manually set the region
            builder = builder.region(Region.EU_CENTRAL_1);
        }
        return builder.build();
    }

    private static String readFromEnv(String key) {
        String result = readFromEnv(key, null);
        if (result == null) {
            throw new RuntimeException("Env key is required but not found: " + key);
        }
        return result;
    }

    private static String readFromEnv(String key, String defaultValue) {
        String result = System.getenv(key);
        if (result == null) {
            return defaultValue;
        }
        return result;
    }
}
