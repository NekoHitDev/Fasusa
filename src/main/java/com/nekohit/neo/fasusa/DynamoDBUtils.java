package com.nekohit.neo.fasusa;

import io.neow3j.protocol.core.response.NeoGetNep17Transfers;
import io.neow3j.types.Hash256;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.util.HashMap;
import java.util.Map;

public class DynamoDBUtils {
    private static final String TABLE_TX_HASH_KEY = "tx_hash";
    private static final String TABLE_NOTIFY_INDEX_KEY = "notify_index";
    private static final String TABLE_FROM_ADDRESS_KEY = "from_address";
    private static final String TABLE_RECEIVED_AMOUNT_KEY = "received_amount";

    private static final String TABLE_GAS_PRICE_KEY = "gas_price";
    private static final String TABLE_PAID_TX_KEY = "paid_tx";

    public static boolean putPurchaseRecordIfNotExists(
            DynamoDbClient client,
            NeoGetNep17Transfers.Nep17Transfer nep17Transfer
    ) {
        try {
            client.putItem(PutItemRequest.builder()
                    .tableName(Env.DYNAMODB_TABLE_NAME)
                    .item(transferToItem(nep17Transfer))
                    // This will make sure <txHash, notifyIndex> is not duplicated
                    .conditionExpression("attribute_not_exists(#KEY)")
                    .expressionAttributeNames(Map.of("#KEY", TABLE_TX_HASH_KEY))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    public static void updatePurchaseRecord(
            DynamoDbClient client,
            NeoGetNep17Transfers.Nep17Transfer nep17Transfer,
            double gasPrice, Hash256 paidTx
    ) {
        client.updateItem(UpdateItemRequest.builder()
                .tableName(Env.DYNAMODB_TABLE_NAME)
                .key(transferToKey(nep17Transfer))
                .updateExpression("SET #PRICE = :price, #PAY = :pay")
                .expressionAttributeNames(Map.of(
                        "#PRICE", TABLE_GAS_PRICE_KEY,
                        "#PAY", TABLE_PAID_TX_KEY
                ))
                .expressionAttributeValues(Map.of(
                        ":price", AttributeValue.builder().n(String.valueOf(gasPrice)).build(),
                        ":pay", AttributeValue.builder().s(paidTx.toString()).build()
                ))
                .build());
    }

    private static Map<String, AttributeValue> transferToItem(
            NeoGetNep17Transfers.Nep17Transfer transfer
    ) {
        Map<String, AttributeValue> result = new HashMap<>();
        result.put(TABLE_TX_HASH_KEY, AttributeValue.builder().s(transfer.getTxHash().toString()).build());
        result.put(TABLE_NOTIFY_INDEX_KEY, AttributeValue.builder().n(String.valueOf(transfer.getTransferNotifyIndex())).build());
        result.put(TABLE_FROM_ADDRESS_KEY, AttributeValue.builder().s(transfer.getTransferAddress()).build());
        result.put(TABLE_RECEIVED_AMOUNT_KEY, AttributeValue.builder().n(transfer.getAmount().toString()).build());
        return result;
    }

    private static Map<String, AttributeValue> transferToKey(
            NeoGetNep17Transfers.Nep17Transfer transfer
    ) {
        Map<String, AttributeValue> result = new HashMap<>();
        result.put(TABLE_TX_HASH_KEY, AttributeValue.builder().s(transfer.getTxHash().toString()).build());
        result.put(TABLE_NOTIFY_INDEX_KEY, AttributeValue.builder().n(String.valueOf(transfer.getTransferNotifyIndex())).build());
        return result;
    }
}
