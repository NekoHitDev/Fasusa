# Fasusa

The tx scanner that receive GAS and send you CAT.

## TODO

Charge some GAS for exchage fee?

## Brief intro

This is a temporary solution before LUSD (from LyreBird) or fUSDT (from Flamingo swap)
goes online on the mainnet. This program allows user purchase CAT token using GAS.

As you may or may not know, the CAT token has a fixed value, which is 1CAT = 0.5USD.
The original idea is to let user send coin to CatToken contract, and the CatToken contract
will mint new CAT tokens based on that fixed value, so user can exchange CAT with
stable coins in one tx, and everything is on-chain, no need to trust any entity.
However, currently there are no USD wrappers or alternative stable coin on the mainnet.
So we have to wait flamingo or LyreBird, or we have to stick with this temporary solution.

## How it works

This is a simple Java program which is started, executed, and then exited.
There is a `Dockerfile` which will build the Java project and package it into a docker image.
Then the docker image need to be uploaded to a private Amazon Elastic Container Registry.
Before we using it, we have to configure a cluster and a task in the Amazon Elastic
Container Service. The task will use this docker image, the same image can work on
both testnet and mainnet based on different env settings.
Then you need to configure a rule in Amazon EventBridge to start this task periodically.

Once the task is started, it will read env settings and initialize the basic services,
including:

+ A neow3j client
+ A DynamoDbClient
+ Average price of GAS/BTC and BTC/BUSD in last 5 minutes

The gas price is calculated by GAS/BTC * BTC/BUSD, then with the fixed rate: 1USD = 2CAT,
it will calculate the GAS to CAT rate. Since this is not a DEX or anything designed
for trading, the price data is not time-sensitive. If you don't like it, then just wait
for flamingo and LyreBird, we will upgrade our contract as soon as they goes to the mainnet.

After everything is prepared, the program will start processing the transfer.
If something goes wrong, like unable to fetch data from binance, the program will
exit, no transfer will be processed. 

The program will ask a N3 node for all Nep17 transfers in the last 30 minutes, then
find all GAS related transfer, find all user triggered transfer, and try to insert them
into DynamoDB. Each transfer will be identified by <txHash, notifyIndex>, and each
pair can and only can be insert once. All transfer that successfully inserted are keep
processing: calculating CAT amount and make the transfer. All failed one means there
is already a same record in the database, it is either being processed by other instances,
or has been processed in the past. The program will attach paid tx hash and current
GAS/USD price after everything is settled.

After all transfers are processed, the program exits.

Normally the transfer should be able to processed in 5~10 minutes. The scanning interval
is 5 minutes. If you didn't receive your CAT token after 10 minutes, just find admins
in the Discord. All we need is a txId to confirm you made the GAS transfer, then
we will send you token, no addition transfer is require. **Be careful with scammers.**

## What could possibly go wrong

This program is ***NOT*** fully tested and is only a temporary solution.
All I need to ensure is that no one can steal those CAT tokens on the mainnet.
If your payment is not accepted by this program, it's already on chain, we
can manually confirm it and process it. Just don't panic.

Here's some potential scenarios that this program will refuse to work:

+ Env settings are not correct: Exits before process any transfer.
+ Cannot fetch price data from Binance: Exits before process any transfer.
+ Not enough CAT token: It may or may not be detected
  + If it's detected, the program will stop processing any transfer.
    The `gas_price` and `paid_tx` will be empty. You have to query the tx info
    to get the block time and process the transfer.
  + If it's not, the program will keep sending tx, but no valid transfer.
    The `gas_price` and `paid_tx` will be filled, but `paid_tx` has no transfer.
+ Run out of gas: An exception will be thrown and no transfer will be processed.

If we run out of something: try to recover it and let the program try again.
It will scan every 5min for all transfer occurred in last 30min. Only manually
process a transfer if:

+ It's in the DynamoDB, but no `paid_tx` and `gas_price`, and the instance is dead.
  This can be confirmed by checking the block time and see the CloudWatch logs.
+ Or, the transfer is not in DynamoDB, but it has been already 30 minutes passed.
  The program will not try to process it in the future.

At this time, you can manually process the transfer, and insert a correct record
into the DynamoDB.

## Potential flaws

Since GAS is not a stable token, the price might change across some perid of time.
For example:
+ The program sold 1 CAT when 1GAS = 12USD. When we need to sell those gas for USD,
  The GAS price drop to 1GAS = 6USD, then we have to pay for the 50% loss, aka 0.25 USD
  per CAT.

So I recommend that we start from a small amount, like preserving 2000 USDT for 8000 CAT for sell.
If all CAT are sold, then we sold the GAS and get USDT. Then we start a new round.
This will prevent us from losing too much money when doing one shot. Multiple shot will
help us ease the price fluctuations.

## Env settings

You can have different setting to make it work on both testnet and mainnet.

### `DEBUG_MODE`

The default value is `false`. When set to `true`:
+ Nodes are connected through proxy
+ AWS region is set to EU_CENTRAL_1

This is only for testing run.

### `NODE_URL`

This decides which node will be use. The node must have `RpcNep17Tracker` plugin installed.

This is required. 

### `SCAN_DURATION_MIN`

The default value is `30`, which means the scan interval is 30 minutes.

### `DYNAMODB_TABLE_NAME`

The DynamoDB table name. It must be created beforehand.

The partition key is `tx_hash`, string; the sort key must be `notify_index`, number.

### `GAS_RECEIVE_ADDRESS`

Which address is used to receive GAS. Can be multi-sig address.


### `CAT_TOKEN_HASH`

The hash of the CatToken's script hash.

### `CAT_ACCOUNT_WIF`

The WIF of account that holding CAT tokens. This must be a normal account,
since transfer CAT needs to sign the tx, which requires the private key.

