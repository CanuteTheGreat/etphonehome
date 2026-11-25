package com.android.billingclient.api;

import org.json.JSONException;

/**
 * Unit tests only: builds a ProductDetails from Play's JSON (its constructor is package-private),
 * so PlayBillingGatewayTest can hand the real BillingFlowParams builder a real product.
 */
public final class TestProducts {
    private TestProducts() {}

    public static ProductDetails productDetails(String json) throws JSONException {
        return new ProductDetails(json);
    }
}
