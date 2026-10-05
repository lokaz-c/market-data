package com.lokaz.marketdata.alpaca;

/** Values for Alpaca's adjustment parameter that this service uses. */
public enum Adjustment {
    /** What we store; adjustments are applied in SQL (bars_split_adjusted). */
    RAW("raw"),
    /** Used to check our SQL view against Alpaca's own split adjustment. */
    SPLIT("split");

    private final String param;

    Adjustment(String param) {
        this.param = param;
    }

    public String param() {
        return param;
    }
}
