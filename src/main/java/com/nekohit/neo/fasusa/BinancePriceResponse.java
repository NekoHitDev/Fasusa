package com.nekohit.neo.fasusa;

import com.google.gson.annotations.SerializedName;

public class BinancePriceResponse {
    @SerializedName("mins")
    private int minutes;

    @SerializedName("price")
    private String price;

    public int getMinutes() {
        return this.minutes;
    }

    public String getPrice() {
        return this.price;
    }
}
