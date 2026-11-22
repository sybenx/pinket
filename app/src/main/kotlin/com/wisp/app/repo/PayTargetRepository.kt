package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wisp.app.nostr.NipA3
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The newest NIP-A3 payment-target list (kind 10133) seen for one account. The
 * full tag list and content are kept so the owner's own list can be re-published
 * with only the Monero entry changed.
 */
@Serializable
data class PayTargetList(
    val pubkey: String,
    val tags: List<List<String>>,
    val content: String = "",
    val createdAt: Long
) {
    /** The account's Monero address if one is listed and looks valid. */
    val moneroAddress: String? get() = NipA3.moneroAddress(tags)

    companion object {
        fun fromEvent(event: NostrEvent): PayTargetList? {
            if (event.kind != NipA3.KIND_PAYMENT_TARGETS) return null
            return PayTargetList(event.pubkey, event.tags, event.content, event.created_at)
        }
    }
}

/** A way Pinket can pay someone. Stored by [key] for the last-used choice. */
enum class PayMethod(val key: String) {
    BITCOIN("bitcoin"),
    MONERO("monero");

    companion object {
        fun fromKey(key: String?): PayMethod? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What an account can be paid with. Bitcoin comes from the profile's lightning
 * address (unchanged from before), Monero from its kind 10133 list.
 *
 * Neither coin is primary: [methods] lists what the account accepts and
 * [initialMethod] picks the viewer's last-used one when it is available.
 */
data class PayOptions(
    val lightningAddress: String?,
    val moneroAddress: String?
) {
    val acceptsBitcoin: Boolean get() = lightningAddress != null
    val acceptsMonero: Boolean get() = moneroAddress != null
    val isEmpty: Boolean get() = !acceptsBitcoin && !acceptsMonero
    /** More than one method, so the send sheet shows the Bitcoin | Monero switch. */
    val hasChoice: Boolean get() = methods.size > 1

    val methods: List<PayMethod>
        get() = buildList {
            if (acceptsBitcoin) add(PayMethod.BITCOIN)
            if (acceptsMonero) add(PayMethod.MONERO)
        }

    /** [lastUsed] if this account accepts it, else the only/first method, else null. */
    fun initialMethod(lastUsed: PayMethod?): PayMethod? =
        lastUsed?.takeIf { it in methods } ?: methods.firstOrNull()

    companion object {
        val NONE = PayOptions(null, null)

        fun of(profile: ProfileData?, payTargets: PayTargetList?): PayOptions = PayOptions(
            lightningAddress = profile?.lud16?.trim()?.ifEmpty { null },
            moneroAddress = payTargets?.moneroAddress
        )
    }
}

/**
 * Per-account cache of kind 10133 payment-target lists, persisted like profiles.
 * Newest event per account wins.
 *
 * Profiles that are already cached are never refetched while scrolling, so this
 * repository also remembers when each account's list was last looked up (found or
 * not) and asks again after [LOOKUP_TTL_SEC].
 */
class PayTargetRepository internal constructor(
    listPrefs: SharedPreferences,
    checkedPrefs: SharedPreferences,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }
) : AutoCloseable {
    constructor(context: Context) : this(
        context.getSharedPreferences("wisp_pay_targets", Context.MODE_PRIVATE),
        context.getSharedPreferences("wisp_pay_targets_checked", Context.MODE_PRIVATE)
    )

    companion object {
        private const val TAG = "PayTargetRepository"
        const val LOOKUP_TTL_SEC = 24 * 60 * 60L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val lists = PersistedMetadataCache<PayTargetList>(
        listPrefs, "pt_",
        encode = { json.encodeToString(it) },
        decode = { json.decodeFromString<PayTargetList>(it) },
        onFailure = { Log.w(TAG, "Pay target write failed", it) }
    )
    /** pubkey -> unix seconds of the last finished lookup. */
    private val checked = PersistedMetadataCache<Long>(
        checkedPrefs, "c_",
        encode = { it.toString() },
        decode = { it.toLong() },
        onFailure = { Log.w(TAG, "Pay target lookup marker write failed", it) }
    )

    /** Stores [event] if it is a kind 10133 newer than what we have. Returns the stored list or null. */
    fun updateFromEvent(event: NostrEvent): PayTargetList? {
        val list = PayTargetList.fromEvent(event) ?: return null
        markChecked(event.pubkey)
        return if (lists.update(event.pubkey, list, event.created_at)) list else null
    }

    fun get(pubkey: String): PayTargetList? = lists.get(pubkey)

    fun moneroAddress(pubkey: String): String? = get(pubkey)?.moneroAddress

    /** True once a lookup for [pubkey] finished recently, whether or not a list was found. */
    fun isFresh(pubkey: String): Boolean {
        val at = checked.get(pubkey) ?: return false
        return clock() - at < LOOKUP_TTL_SEC
    }

    fun needsLookup(pubkey: String): Boolean = !isFresh(pubkey)

    fun markChecked(pubkey: String) {
        val now = clock()
        checked.update(pubkey, now, now)
    }

    fun markChecked(pubkeys: Collection<String>) = pubkeys.forEach(::markChecked)

    suspend fun flush() {
        lists.flush()
        checked.flush()
    }

    override fun close() {
        lists.close()
        checked.close()
    }

    suspend fun shutdown() {
        lists.shutdown()
        checked.shutdown()
    }
}
