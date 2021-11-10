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
import io.neow3j.types.Hash256;
import io.neow3j.types.NeoVMStateType;
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
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;

public class TxScanner implements AutoCloseable {
    private final Duration SCAN_DURATION = Duration.ofMinutes(Env.SCAN_DURATION_MIN);

    private final Logger logger = LoggerFactory.getLogger(TxScanner.class);
    private final Gson gson = new GsonBuilder().create();

    private final OkHttpClient httpClient = Env.getHttpClient();
    private final Neow3j NEOW3J = Neow3j.build(new HttpService(Env.NODE_URL, this.httpClient));
    private final DynamoDbClient dynamoDbClient = Env.getDynamoDbClient();
    // A single thread pool for callback
    private final ExecutorService threadPool = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy()
    );
    private final List<CompletableFuture<Void>> futures = new LinkedList<>();

    private final FungibleToken CAT_TOKEN = new FungibleToken(Env.CAT_TOKEN_SCRIPT_HASH, this.NEOW3J);
    // 0.05GAS for exchange fee
    private final BigInteger EXCHANGE_FEE = BigInteger.valueOf(50_0000L);
    private final double GAS_TO_USD;
    private final BigInteger GAS_TO_CAT;

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
        // 1GAS = ?CAT (represented in fraction: 1CAT = 1_00)
        this.GAS_TO_CAT = BigInteger.valueOf((long) (this.GAS_TO_USD * 2 * 100));
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

        this.NEOW3J.getNep17Transfers(Env.GAS_RECEIVE_ADDRESS, until)
                .send()
                .getNep17Transfer()
                .getReceived()
                .stream()
                // find all GasToken related transfer
                .filter(transfer -> transfer.getAssetHash().equals(GasToken.SCRIPT_HASH))
                // find all non-null address
                .filter(transfer -> transfer.getTransferAddress() != null)
                // process
                .forEach(transfer -> this.processTransfer(transfer, false));
    }

    public void processTxOverride(String txId) throws IOException {
        Hash256 txHash = new Hash256(txId);
        var transaction = this.NEOW3J.getTransaction(txHash).send().getTransaction();
        if (transaction == null) {
            this.logger.error("Tx: {} not found", txId);
            return;
        }

        if (transaction.getBlockTime() + this.SCAN_DURATION.toMillis() >= System.currentTimeMillis()) {
            this.logger.error("Tx: {} might be processed in the future. Rejected.", txId);
            return;
        }

        this.NEOW3J.getNep17Transfers(Env.GAS_RECEIVE_ADDRESS,
                        new Date(transaction.getBlockTime() - 10),
                        new Date(transaction.getBlockTime() + 10)
                ).send()
                .getNep17Transfer()
                .getReceived()
                .stream()
                // find that tx
                .filter(transfer -> transfer.getTxHash().equals(txHash))
                // find all GasToken related transfer
                .filter(transfer -> transfer.getAssetHash().equals(GasToken.SCRIPT_HASH))
                // find all non-null address
                .filter(transfer -> transfer.getTransferAddress() != null)
                // process
                .forEach(transfer -> this.processTransfer(transfer, true));
    }

    private void processTransfer(NeoGetNep17Transfers.Nep17Transfer nep17Transfer, boolean override) {
        if (DynamoDBUtils.putPurchaseRecordIfNotExists(this.dynamoDbClient, nep17Transfer) || override) {
            // this is a new transfer, process it
            // or, with override, process it anyway

            // calculate the amount, sub the exchange fee, multiply by rate,
            // and divide into fraction representation
            // Note: The result might be negative if amount is smaller than exchange fee,
            // so we cut to zero by amount = Max(result, 0)
            BigInteger amount = nep17Transfer.getAmount().subtract(this.EXCHANGE_FEE)
                    .multiply(this.GAS_TO_CAT).divide(BigInteger.TEN.pow(8))
                    // Cut to zero
                    .max(BigInteger.ZERO);

            try {
                // sign the tx
                Transaction tx = this.CAT_TOKEN.transfer(
                        Env.CAT_ASSET_ACCOUNT,
                        Hash160.fromAddress(nep17Transfer.getTransferAddress()),
                        amount
                ).sign();
                // send it
                NeoSendRawTransaction resp = tx.send();
                // check error
                resp.throwOnError();
                // if everything is ok, set the callback
                futures.add(CompletableFuture.runAsync(() -> {
                    // wait the tx is confirmed
                    while (tx.getApplicationLog() == null) {
                        try {
                            TimeUnit.SECONDS.sleep(5);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    this.logger.info("Paid {} CAT to address {}. Tx: 0x{}",
                            amount, nep17Transfer.getTransferAddress(), tx.getTxId());
                    // get the execution
                    var execution = tx.getApplicationLog().getExecutions().get(0);
                    // VM is HALT and transfer return true
                    if (execution.getState() == NeoVMStateType.HALT && execution.getStack().size() > 0 && execution.getStack().get(0).getBoolean()) {
                        // then the transfer is confirmed, update database
                        DynamoDBUtils.updatePurchaseRecord(this.dynamoDbClient, nep17Transfer, this.GAS_TO_USD, tx.getTxId());
                    } else {
                        if (execution.getState() != NeoVMStateType.HALT) {
                            this.logger.error("Tx: 0x{} has VM status {}!", tx.getTxId(), execution.getState());
                        } else {
                            this.logger.error("Tx: 0x{}, transfer failed!", tx.getTxId());
                        }
                    }
                }, this.threadPool));
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }

    @Override
    public void close() {
        this.threadPool.shutdown();
        futures.forEach(f -> {
            try {
                f.get();
            } catch (Exception e) {
               logger.error("Error when processing callback", e);
            }
        });
        this.dynamoDbClient.close();
    }
}
