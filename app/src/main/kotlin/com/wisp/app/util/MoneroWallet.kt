package com.wisp.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.math.BigDecimal

/** Hands a Monero payment to the phone's wallet app. Pinket never learns whether it was paid. */
object MoneroWallet {
    /**
     * Opens `monero:<address>?tx_amount=<amount>` in whatever wallet handles it.
     * Returns false when no app can open it (or the one found cannot be started); show the QR / copy fallback then.
     */
    fun open(context: Context, address: String, amount: BigDecimal?): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(Monero.paymentUri(address, amount)))
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            // A handler this app may not start: fall back the same way.
            false
        }
    }
}
