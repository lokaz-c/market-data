package com.lokaz.marketdata.alpaca;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/** {"corporate_actions": {"forward_splits": [...], "reverse_splits": [...]}, "next_page_token": ...}. */
@JsonIgnoreProperties(ignoreUnknown = true)
record CorporateActionsResponse(
        @JsonProperty("corporate_actions") @Nullable Actions corporateActions,
        @JsonProperty("next_page_token") @Nullable String nextPageToken) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Actions(
            @JsonProperty("forward_splits") @Nullable List<AlpacaSplit> forwardSplits,
            @JsonProperty("reverse_splits") @Nullable List<AlpacaSplit> reverseSplits) {
    }

    List<AlpacaSplit> splits() {
        var result = new ArrayList<AlpacaSplit>();
        if (corporateActions != null) {
            if (corporateActions.forwardSplits() != null) {
                result.addAll(corporateActions.forwardSplits());
            }
            if (corporateActions.reverseSplits() != null) {
                result.addAll(corporateActions.reverseSplits());
            }
        }
        return result;
    }
}
