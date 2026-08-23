package com.covertwogames.dozeoff.util

import android.app.Activity
import android.util.Log
import com.android.billingclient.api.*

class BillingManager(
    private val activity: Activity,
    private val onPurchaseComplete: (String) -> Unit,
    private val onError: (String) -> Unit
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
        const val PRODUCT_COFFEE = "tip_coffee"       // $2.99
        const val PRODUCT_LUNCH = "tip_lunch"          // $4.99
        const val PRODUCT_HIGH_FIVE = "tip_highfive"   // $9.99
    }

    private var billingClient: BillingClient? = null
    private val productDetailsList = mutableMapOf<String, ProductDetails>()

    fun initialize() {
        billingClient = BillingClient.newBuilder(activity)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()

        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing client connected")
                    queryProducts()
                } else {
                    Log.e(TAG, "Billing setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.d(TAG, "Billing service disconnected")
            }
        })
    }

    private fun queryProducts() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_COFFEE)
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_LUNCH)
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_HIGH_FIVE)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient?.queryProductDetailsAsync(params) { billingResult, queryResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val details = queryResult.productDetailsList
                for (productDetails in details) {
                    this.productDetailsList[productDetails.productId] = productDetails
                }
                Log.d(TAG, "Loaded ${details.size} products")
            } else {
                Log.e(TAG, "Product query failed: ${billingResult.debugMessage}")
            }
        }
    }

    fun getFormattedPrice(productId: String): String? {
        return productDetailsList[productId]
            ?.oneTimePurchaseOfferDetails
            ?.formattedPrice
    }

    fun launchPurchase(productId: String) {
        val productDetails = productDetailsList[productId]
        if (productDetails == null) {
            onError("Product not available. Please try again later.")
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .build()
        )

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val result = billingClient?.launchBillingFlow(activity, flowParams)
        if (result?.responseCode != BillingClient.BillingResponseCode.OK) {
            onError("Could not start purchase flow.")
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        handlePurchase(purchase)
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                // User cancelled, do nothing
            }
            else -> {
                onError("Purchase failed. Please try again.")
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (!purchase.isAcknowledged) {
            val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()

            billingClient?.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    consumePurchase(purchase)
                }
            }
        }
    }

    private fun consumePurchase(purchase: Purchase) {
        val consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient?.consumeAsync(consumeParams) { billingResult, _ ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val productId = purchase.products.firstOrNull() ?: "tip"
                val label = when (productId) {
                    PRODUCT_COFFEE -> "Coffee"
                    PRODUCT_LUNCH -> "Lunch"
                    PRODUCT_HIGH_FIVE -> "High Five"
                    else -> "Tip"
                }
                onPurchaseComplete(label)
            } else {
                onError("Could not complete purchase.")
            }
        }
    }

    fun destroy() {
        billingClient?.endConnection()
        billingClient = null
    }
}
