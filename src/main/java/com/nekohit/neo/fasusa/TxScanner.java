package com.nekohit.neo.fasusa;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.neow3j.contract.FungibleToken;
import io.neow3j.contract.GasToken;
import io.neow3j.protocol.Neow3j;
import io.neow3j.protocol.core.response.NeoGetNep17Transfers;
import io.neow3j.protocol.core.response.NeoSendRawTransaction;
import io.neow3j.protocol.http.HttpService;
import io.neow3j.transaction.Transaction;
import io.neow3j.types.Hash160;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Date;
import java.util.stream.Stream;

public class TxScanner implements AutoCloseable {
    private final Duration SCAN_DURATION = Duration.ofMinutes(Env.SCAN_DURATION_MIN);

    private final Logger logger = LoggerFactory.getLogger(TxScanner.class);
    private final Gson gson = new GsonBuilder().create();

    private final OkHttpClient httpClient = Env.getHttpClient();
    private final Neow3j NEOW3J = Neow3j.build(new HttpService(Env.NODE_URL, this.httpClient));
    private final DynamoDbClient dynamoDbClient = Env.getDynamoDbClient();

    private final FungibleToken CAT_TOKEN = new FungibleToken(Env.CAT_TOKEN_SCRIPT_HASH, this.NEOW3J);

    private final double GAS_TO_USD;

    public TxScanner() throws IOException {
        this.logger.info("Scan duration: {}", this.SCAN_DURATION.toString());
        BigDecimal gasBTC = this.getPrice("GASBTC");
        if (gasBTC == null) {
            throw new RuntimeException("Failed to fetch GAS/BTC price");
        }
        BigDecimal btcBUSD = this.getPrice("BTCBUSD");
        if (btcBUSD == null) {
            throw new RuntimeException("Failed to fetch BTC/BUSD price");
        }
        // 1GAS=?BTC * 1BTC=?USD -> 1GAS=?USD
        this.GAS_TO_USD = gasBTC.multiply(btcBUSD).doubleValue();
    }

    private BigDecimal getPrice(String pair) throws IOException {
        Response resp = this.httpClient.newCall(new Request.Builder()
                .url("https://api.binance.com/api/v3/avgPrice?symbol=" + pair)
                .build()).execute();
        ResponseBody body = resp.body();
        if (resp.isSuccessful() && body != null) {
            BinancePriceResponse price = this.gson.fromJson(body.string(), BinancePriceResponse.class);
            return new BigDecimal(price.getPrice());
        }
        return null;
    }

    public void scan() throws IOException {
        Date until = new Date(System.currentTimeMillis() - this.SCAN_DURATION.toMillis());
        this.processTransfer(
                this.NEOW3J.getNep17Transfers(Env.GAS_RECEIVE_ADDRESS, until)
                        .send()
                        .getNep17Transfer()
                        .getReceived()
                        .stream()
                        // find all GasToken related transfer
                        .filter(transfer -> transfer.getAssetHash().equals(GasToken.SCRIPT_HASH))
                        // find all non-null address
                        .filter(transfer -> transfer.getTransferAddress() != null)
        );
    }

    private void processTransfer(Stream<NeoGetNep17Transfers.Nep17Transfer> transferStream) throws IOException {
        // 1GAS = ?CAT (represented in fraction: 1CAT = 1_00)
        BigInteger gasToCat = BigInteger.valueOf((long) (this.GAS_TO_USD * 2 * 100));
        var currentBalanceRef = new Object() {
            BigInteger currentBalance = TxScanner.this.CAT_TOKEN.getBalanceOf(Env.CAT_ASSET_ACCOUNT);
        };
        transferStream.forEach(nep17Transfer -> {
            if (DynamoDBUtils.putPurchaseRecordIfNotExists(this.dynamoDbClient, nep17Transfer)) {
                // this is a new transfer, process it
                BigInteger amount = nep17Transfer.getAmount().multiply(gasToCat).divide(BigInteger.TEN.pow(8));
                if (currentBalanceRef.currentBalance.compareTo(amount) < 0) {
                    // not enough balances
                    throw new RuntimeException("Not enough CAT token.");
                }

                try {
                    Transaction tx = this.CAT_TOKEN.transfer(
                            Env.CAT_ASSET_ACCOUNT,
                            Hash160.fromAddress(nep17Transfer.getTransferAddress()),
                            amount
                    ).sign();
                    NeoSendRawTransaction resp = tx.send();
                    resp.throwOnError();
                    // update balance
                    currentBalanceRef.currentBalance = currentBalanceRef.currentBalance.subtract(amount);
                    this.logger.info("Paid {} CAT to address {}. Tx: 0x{}",
                            amount, nep17Transfer.getTransferAddress(), tx.getTxId());
                    // record payment tx
                    DynamoDBUtils.updatePurchaseRecord(this.dynamoDbClient, nep17Transfer, this.GAS_TO_USD, tx.getTxId());
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }
        });
    }

    @Override
    public void close() {
        this.dynamoDbClient.close();
    }
}
