package com.wisp.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.Bolt11
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.Nip57
import com.wisp.app.nostr.Nip78
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.RelayEvent
import com.wisp.app.repo.SigningMode
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.BalanceUnit
import com.wisp.app.repo.NwcRepository
import com.wisp.app.repo.WalletMode
import com.wisp.app.repo.WalletModeRepository
import com.wisp.app.repo.WalletProvider
import android.util.Log
import com.wisp.app.repo.WalletTransaction
import com.wisp.app.repo.ZapSender
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield

sealed class WalletState {
    object NotConnected : WalletState()
    object Connecting : WalletState()
    data class Connected(val balanceMsats: Long) : WalletState()
    data class Error(val message: String) : WalletState()
}


sealed class NwcRestoreState {
    object Idle : NwcRestoreState()
    object Searching : NwcRestoreState()
    data class Found(val uri: String, val createdAt: Long) : NwcRestoreState()
    object NotFound : NwcRestoreState()
}

sealed class WalletPage {
    object Home : WalletPage()
    object ModeSelection : WalletPage()
    object NwcSetup : WalletPage()
    data class NwcExport(val connectionString: String) : WalletPage()
    object SendInput : WalletPage()
    object ScanQR : WalletPage()
    data class SendAmount(val address: String) : WalletPage()
    data class SendConfirm(
        val invoice: String,
        val amountSats: Long?,
        val paymentHash: String?,
        val description: String?
    ) : WalletPage()
    data class Sending(val invoice: String) : WalletPage()
    data class SendResult(val success: Boolean, val message: String) : WalletPage()
    object ReceiveAmount : WalletPage()
    data class ReceiveInvoice(val invoice: String, val amountSats: Long) : WalletPage()
    data class ReceiveSuccess(val amountSats: Long) : WalletPage()
    object Transactions : WalletPage()
    object Settings : WalletPage()
    object DeleteWalletConfirm : WalletPage()
}

class WalletViewModel(
    val nwcRepo: NwcRepository,
    val walletModeRepo: WalletModeRepository,
    val eventRepo: EventRepository,
    val relayPool: RelayPool,
    val keyRepo: KeyRepository,
    /** The only place that signs the NWC backup (FeedViewModel.listWriter). */
    private val listWriter: ListWriter? = null,
) : ViewModel() {
    private val accountScope = AccountSessionScope(viewModelScope)
    private var accountSuspended = false

    private val _walletMode = MutableStateFlow(walletModeRepo.getMode(nwcRepo.hasConnection()))
    val walletMode: StateFlow<WalletMode> = _walletMode

    private val _balanceUnit = MutableStateFlow(walletModeRepo.getBalanceUnit())
    val balanceUnit: StateFlow<BalanceUnit> = _balanceUnit

    fun setBalanceUnit(unit: BalanceUnit) {
        walletModeRepo.setBalanceUnit(unit)
        _balanceUnit.value = unit
    }

    private val activeProvider: WalletProvider
        get() = nwcRepo

    private val _walletState = MutableStateFlow<WalletState>(
        if (activeProvider.hasConnection()) WalletState.Connecting else WalletState.NotConnected
    )
    val walletState: StateFlow<WalletState> = _walletState

    private val _connectionString = MutableStateFlow("")
    val connectionString: StateFlow<String> = _connectionString

    /** Live status lines emitted during connection */
    private val _statusLines = MutableStateFlow<List<String>>(emptyList())
    val statusLines: StateFlow<List<String>> = _statusLines

    // Page navigation
    private val pageStack = mutableListOf<WalletPage>(WalletPage.Home)
    private val _currentPage = MutableStateFlow<WalletPage>(WalletPage.Home)
    val currentPage: StateFlow<WalletPage> = _currentPage

    // Send flow
    private val _sendInput = MutableStateFlow("")
    val sendInput: StateFlow<String> = _sendInput

    private val _sendAmount = MutableStateFlow("")
    val sendAmount: StateFlow<String> = _sendAmount

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError

    // Receive flow
    private val _receiveAmount = MutableStateFlow("")
    val receiveAmount: StateFlow<String> = _receiveAmount

    // Transactions
    private val _transactions = MutableStateFlow<List<WalletTransaction>>(emptyList())
    val transactions: StateFlow<List<WalletTransaction>> = _transactions

    private val _transactionsError = MutableStateFlow<String?>(null)
    val transactionsError: StateFlow<String?> = _transactionsError

    /** Incremented when new profiles arrive for transaction counterparties, to trigger UI refresh. */
    private val _profileRefreshKey = MutableStateFlow(0)
    val profileRefreshKey: StateFlow<Int> = _profileRefreshKey

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    private val _hasMoreTransactions = MutableStateFlow(true)
    val hasMoreTransactions: StateFlow<Boolean> = _hasMoreTransactions

    // Lightning address
    private val _lightningAddress = MutableStateFlow<String?>(null)
    val lightningAddress: StateFlow<String?> = _lightningAddress

    // NWC node info (alias + supported methods) — fetched once post-connect.
    val nwcNodeAlias: StateFlow<String?> = nwcRepo.nodeAlias
    val nwcSupportedMethods: StateFlow<List<String>> = nwcRepo.supportedMethods

    // NWC connection metadata for the Wallet Info expandable in settings.
    val nwcConnectionInfo: StateFlow<NwcRepository.ConnectionInfo?> = nwcRepo.connectionInfo

    // NWC connection-string restore from NIP-78 backup (per NWC_BACKUP_PARITY.md)
    private val _nwcRestoreState = MutableStateFlow<NwcRestoreState>(NwcRestoreState.Idle)
    val nwcRestoreState: StateFlow<NwcRestoreState> = _nwcRestoreState
    private var nwcRestoreJob: Job? = null

    /**
     * Wipe per-wallet display state so swapping wallets (disconnect,
     * delete, account switch) doesn't carry the previous wallet's
     * transactions / pagination / lightning address into the new
     * wallet's UI. Mirrors iOS `WalletStore.clearDisplayState`.
     */
    private fun clearWalletDisplayState() {
        _transactions.value = emptyList()
        _transactionsError.value = null
        _hasMoreTransactions.value = true
        _lightningAddress.value = null
    }

    private var connectJob: Job? = null
    private var statusCollectJob: Job? = null
    private var connectionMonitorJob: Job? = null
    private val httpClient get() = com.wisp.app.relay.HttpClientFactory.createRelayClient()

    init {
        // NWC backup queries must bypass event dedup so events already seen
        // earlier in the session are still returned.
        relayPool.registerDedupBypass("nwc-restore-")

        if (_walletMode.value == WalletMode.NWC && nwcRepo.hasConnection()) {
            _connectionString.value = nwcRepo.getConnectionString() ?: ""
            connectNwcWallet(nwcRepo.getConnectionString() ?: "")
        }

        // Auto-navigate to success screen when an incoming payment is received
        viewModelScope.launch {
            nwcRepo.paymentReceived.collect { amountMsats ->
                if (!accountSuspended && _walletMode.value == WalletMode.NWC && _currentPage.value is WalletPage.ReceiveInvoice) {
                    val amountSats = amountMsats / 1000
                    pageStack.removeAt(pageStack.lastIndex)
                    val successPage = WalletPage.ReceiveSuccess(amountSats)
                    pageStack.add(successPage)
                    _currentPage.value = successPage
                    refreshBalance()
                }
            }
        }
    }

    // --- Navigation ---

    fun navigateTo(page: WalletPage) {
        pageStack.add(page)
        _currentPage.value = page
    }

    fun navigateBack(): Boolean {
        if (pageStack.size <= 1) return false
        pageStack.removeAt(pageStack.lastIndex)
        _currentPage.value = pageStack.last()
        return true
    }

    fun navigateHome() {
        pageStack.clear()
        pageStack.add(WalletPage.Home)
        _currentPage.value = WalletPage.Home
        _sendInput.value = ""
        _sendAmount.value = ""
        _sendError.value = null
        _receiveAmount.value = ""
    }

    val isOnHome: Boolean get() = pageStack.size <= 1

    // --- Wallet Mode Selection ---

    fun selectNwcMode() {
        navigateTo(WalletPage.NwcSetup)
        searchNwcBackup()
    }

    // --- NWC backup (NIP-78 kind 30078, d=nwc-wallet-backup) ---
    //
    // Cross-platform with iOS per `NWC_BACKUP_PARITY.md`. Publish on every
    // successful connect (best-effort); search on NWC setup screen open and
    // surface a "Restore previous wallet" affordance when found.

    private fun searchNwcBackup() {
        val signer = buildSigner() ?: run {
            _nwcRestoreState.value = NwcRestoreState.NotFound
            return
        }
        // Don't re-search while one is in flight or already has a result the
        // user hasn't dismissed yet.
        if (_nwcRestoreState.value is NwcRestoreState.Searching) return
        nwcRestoreJob?.cancel()
        _nwcRestoreState.value = NwcRestoreState.Searching
        nwcRestoreJob = accountScope.launch {
            try {
                relayPool.ensureWriteRelaysConnected()
                val pubkey = signer.pubkeyHex
                val subId = "nwc-restore-${System.currentTimeMillis()}"
                val filter = Nip78.nwcBackupFilter(pubkey)
                val seenIds = mutableSetOf<String>()
                val events = mutableListOf<NostrEvent>()
                var eoseCount = 0
                val collectJob = launch {
                    relayPool.relayEvents.collect { relayEvent: RelayEvent ->
                        if (relayEvent.subscriptionId == subId && seenIds.add(relayEvent.event.id)) {
                            events.add(relayEvent.event)
                        }
                    }
                }
                val eoseJob = launch {
                    relayPool.eoseSignals.collect { id ->
                        if (id == subId) eoseCount++
                    }
                }
                yield()
                val allCount = relayPool.getRelayUrls().size
                val minEose = (allCount * 2 + 2) / 3
                relayPool.sendToAll(ClientMessage.req(subId, filter))
                withTimeoutOrNull(10_000) {
                    while (eoseCount < allCount) {
                        delay(200)
                        if (eoseCount >= minEose && events.isNotEmpty()) break
                    }
                }
                collectJob.cancel()
                eoseJob.cancel()
                relayPool.closeOnAllRelays(subId)

                val newest = events
                    .filter { !it.content.isBlank() }
                    .maxByOrNull { it.created_at }
                if (newest == null) {
                    _nwcRestoreState.value = NwcRestoreState.NotFound
                    return@launch
                }
                val uri = withContext(Dispatchers.Default) {
                    Nip78.decryptNwcBackup(signer, newest)
                }
                if (uri.isNullOrBlank()) {
                    _nwcRestoreState.value = NwcRestoreState.NotFound
                } else {
                    // Don't offer to restore if it's already the active connection.
                    val active = nwcRepo.getConnectionString()
                    if (active != null && active.trim() == uri.trim()) {
                        _nwcRestoreState.value = NwcRestoreState.NotFound
                    } else {
                        _nwcRestoreState.value = NwcRestoreState.Found(uri, newest.created_at)
                    }
                }
            } catch (_: Exception) {
                _nwcRestoreState.value = NwcRestoreState.NotFound
            }
        }
    }

    fun restoreFromNwcBackup() {
        val state = _nwcRestoreState.value
        if (state is NwcRestoreState.Found) {
            _nwcRestoreState.value = NwcRestoreState.Idle
            _connectionString.value = state.uri
            connectNwcWallet(state.uri, verifySetup = true)
        }
    }

    fun dismissNwcRestore() {
        nwcRestoreJob?.cancel()
        _nwcRestoreState.value = NwcRestoreState.Idle
    }

    /**
     * Back the URI up through the list writer: it starts from your newest backup, writes nothing when that already
     * holds this URI, and keeps its tags. Quiet (no messages): a background backup.
     */
    private suspend fun publishNwcBackup(uri: String) {
        val w = listWriter ?: return
        val signer = buildSigner() ?: return
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return
        try {
            relayPool.ensureWriteRelaysConnected()
            val result = w.setAppData(Nip78.NWC_BACKUP_D_TAG, Nip78.NWC_BACKUP_FIRST_TAGS, quiet = true) { old ->
                Nip78.nwcBackupContent(signer, old, trimmed)
            }
            Log.d("NwcBackup", "backup: $result")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("NwcBackup", "backup failed (non-fatal): ${e.message}")
        }
    }

    // --- NWC Connection ---

    fun updateConnectionString(value: String) {
        _connectionString.value = value
    }

    fun connectNwcWallet(
        uri: String = _connectionString.value,
        silent: Boolean = false,
        verifySetup: Boolean = false
    ) {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return

        val parsed = com.wisp.app.nostr.Nip47.parseConnectionString(trimmed)
        if (parsed == null) {
            _walletState.value = WalletState.Error("Invalid connection string")
            return
        }

        walletModeRepo.setMode(WalletMode.NWC)
        _walletMode.value = WalletMode.NWC

        _statusLines.value = emptyList()
        if (!silent) _walletState.value = WalletState.Connecting
        nwcRepo.saveConnectionString(trimmed)
        _connectionString.value = trimmed

        // Extract lud16 from NWC URI if present
        if (parsed.lud16 != null) {
            _lightningAddress.value = parsed.lud16
        }

        startStatusCollection(nwcRepo)
        nwcRepo.connect()
        // A silent reconnect (account switch back, app resume) does not back the URI up again.
        startConnectionMonitor(nwcRepo, verifySetup = verifySetup, backup = !silent)
    }

    fun showNwcExport() {
        val uri = nwcRepo.getConnectionString() ?: return
        navigateTo(WalletPage.NwcExport(uri))
    }

    // --- Shared connection helpers ---

    private fun startStatusCollection(provider: WalletProvider) {
        statusCollectJob?.cancel()
        statusCollectJob = accountScope.launch {
            provider.statusLog.collect { line ->
                _statusLines.value = _statusLines.value + line
            }
        }
    }

    private fun startConnectionMonitor(provider: WalletProvider, verifySetup: Boolean = false, backup: Boolean = true) {
        connectJob?.cancel()
        val timeoutMs = 20_000L
        connectJob = accountScope.launch {
            val connected = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                provider.isConnected.first { it }
            }
            if (connected == null && _walletState.value !is WalletState.Connected) {
                _statusLines.value = _statusLines.value + "Connection timed out"
                _walletState.value = WalletState.Error("Connection timed out")
            }
        }

        connectionMonitorJob?.cancel()
        connectionMonitorJob = accountScope.launch {
            // Verify at most once per setup connect — a relay auto-reconnect
            // re-emits connected=true and only needs the balance refetch,
            // not another verification round-trip.
            var verified = false
            var backedUp = false
            provider.isConnected.collect { connected ->
                if (connected) {
                    // Opening the subscription says nothing about whether
                    // the wallet service still answers — a revoked URI
                    // "connects" fine and the dashboard's balance fetch
                    // would only fail, silently, later with no explanation.
                    // One short round-trip proves the service is alive so
                    // setup can alert within seconds. Only the setup flow
                    // (paste / restore-from-backup) verifies; app-launch
                    // and account-switch reconnects keep the fast path.
                    var verifiedBalanceMsats: Long? = null
                    if (verifySetup && provider === nwcRepo && !verified) {
                        verified = true
                        val outcome = nwcRepo.verify(NWC_VERIFY_TIMEOUT_MS)
                        if (outcome !is NwcRepository.VerifyOutcome.Confirmed) {
                            failNwcSetup(nwcSetupFailureMessage(outcome))
                            return@collect
                        }
                        // verify() just made the get_balance round-trip —
                        // reuse its answer instead of sending the same
                        // request again. Only this first emission skips the
                        // refetch; a later reconnect re-fetches.
                        verifiedBalanceMsats = outcome.balanceMsats
                    }
                    val result = verifiedBalanceMsats?.let { Result.success(it) }
                        ?: provider.fetchBalance()
                    result.fold(
                        onSuccess = { balanceMsats ->
                            _walletState.value = WalletState.Connected(balanceMsats)
                        },
                        onFailure = { e ->
                            _walletState.value = WalletState.Error(e.message ?: "Failed to fetch balance")
                        }
                    )
                    // Fetch NWC node info (alias / methods) so the dashboard top
                    // bar and Wallet Info expandable can render it.
                    if (provider === nwcRepo) {
                        launch { nwcRepo.fetchNodeInfo() }
                        // Best-effort cross-device backup of the URI per
                        // NWC_BACKUP_PARITY.md, once per connect (a wallet swap
                        // connects again and replaces the prior backup; the
                        // writer writes nothing when the backup already holds it).
                        val uri = nwcRepo.getConnectionString()
                        if (backup && !backedUp && !uri.isNullOrBlank()) {
                            backedUp = true
                            launch { publishNwcBackup(uri) }
                        }
                    }
                    // Once the wallet finishes connecting, leave the setup
                    // screen and land on Home — otherwise currentPage stays
                    // at NwcSetup and the Scaffold's "Wallet"
                    // TopAppBar keeps showing over the dashboard. Guarded
                    // to the setup pages so a connect-during-navigation
                    // doesn't pop the user out of an unrelated sub-page.
                    val page = _currentPage.value
                    if (page is WalletPage.NwcSetup) {
                        pageStack.clear()
                        pageStack.add(WalletPage.Home)
                        _currentPage.value = WalletPage.Home
                    }
                }
            }
        }
    }

    /**
     * Tear a failed NWC setup attempt down and leave the reason on the error
     * state the setup sheet reads. Cancelling the status collector first
     * stops the wallet's still-buffered status lines from appending after
     * the failure lands. The URI stays saved either way — an offline wallet
     * may come back, and pasting a new string overwrites it.
     *
     * Runs inside connectionMonitorJob's collect: cancelling that job here
     * means every step afterwards must be non-suspend from the caller's
     * perspective — the repo teardown's join ([disconnectAndJoin], which
     * awaits the repository's own scope) is dispatched to the ViewModel
     * scope, since awaiting from the just-cancelled coroutine would throw
     * CancellationException at the suspension point.
     */
    private fun failNwcSetup(message: String) {
        statusCollectJob?.cancel()
        statusCollectJob = null
        connectJob?.cancel()
        connectionMonitorJob?.cancel()
        _walletState.value = WalletState.Error(message)
        viewModelScope.launch { nwcRepo.disconnectAndJoin() }
    }

    fun refreshBalance() {
        accountScope.launch {
            val result = activeProvider.fetchBalance()
            result.fold(
                onSuccess = { balanceMsats ->
                    _walletState.value = WalletState.Connected(balanceMsats)
                },
                onFailure = { e ->
                    _walletState.value = WalletState.Error(e.message ?: "Failed to fetch balance")
                }
            )
        }
    }

    fun deleteWallet() {
        connectJob?.cancel()
        statusCollectJob?.cancel()
        connectionMonitorJob?.cancel()

        nwcRepo.disconnect()
        nwcRepo.clearConnection()

        walletModeRepo.setMode(WalletMode.NONE)
        _walletMode.value = WalletMode.NONE
        _walletState.value = WalletState.NotConnected
        _connectionString.value = ""
        _statusLines.value = emptyList()
        clearWalletDisplayState()

        pageStack.clear()
        pageStack.add(WalletPage.Home)
        _currentPage.value = WalletPage.Home
    }

    fun disconnectWallet() {
        connectJob?.cancel()
        statusCollectJob?.cancel()
        connectionMonitorJob?.cancel()

        nwcRepo.disconnect()
        nwcRepo.clearConnection()

        walletModeRepo.setMode(WalletMode.NONE)
        _walletMode.value = WalletMode.NONE
        _walletState.value = WalletState.NotConnected
        _connectionString.value = ""
        _statusLines.value = emptyList()
        clearWalletDisplayState()
        navigateHome()
    }

    /**
     * Suspend the active wallet connection for an account switch without clearing
     * stored credentials. The new account's wallet will be loaded by refreshState()
     * after the repo reload completes.
     */
    suspend fun suspendForAccountSwitch() {
        accountSuspended = true
        navigateHome()
        accountScope.stop()
        nwcRepo.disconnectAndJoin()

        _walletMode.value = WalletMode.NONE
        _walletState.value = WalletState.NotConnected
        _connectionString.value = ""
        _statusLines.value = emptyList()
        clearWalletDisplayState()
        navigateHome()
        _isLoading.value = false
        _isLoadingMore.value = false
        _nwcRestoreState.value = NwcRestoreState.Idle
    }

    fun refreshState() {
        accountScope.start()
        accountSuspended = false
        _balanceUnit.value = walletModeRepo.getBalanceUnit()
        _walletMode.value = walletModeRepo.getMode(nwcRepo.hasConnection())
        val mode = _walletMode.value
        val provider = activeProvider

        if (mode != WalletMode.NWC || !provider.hasConnection()) {
            _walletState.value = WalletState.NotConnected
            return
        }

        if (provider.isConnected.value) {
            refreshBalance()
        } else if (_walletState.value is WalletState.Connected) {
            // Was previously connected — reconnect silently
            connectNwcWallet(nwcRepo.getConnectionString() ?: "", silent = true)
        } else {
            connectNwcWallet(nwcRepo.getConnectionString() ?: "")
        }
    }

    // --- Send flow ---

    fun updateSendInput(value: String) {
        _sendInput.value = value
        _sendError.value = null
    }

    fun setSendAmount(value: String) {
        _sendAmount.value = value
    }

    fun updateSendAmount(digit: Char) {
        val current = _sendAmount.value
        if (current.length < 10) {
            _sendAmount.value = current + digit
        }
    }

    fun sendAmountBackspace() {
        val current = _sendAmount.value
        if (current.isNotEmpty()) {
            _sendAmount.value = current.dropLast(1)
        }
    }

    fun processInput(input: String = _sendInput.value) {
        val trimmed = input.trim()
            .removePrefix("lightning:").removePrefix("LIGHTNING:")
            .let { it.removePrefix("bitcoin:").removePrefix("BITCOIN:") }
        _sendError.value = null

        when {
            trimmed.lowercase().startsWith("lnbc") -> {
                val decoded = Bolt11.decode(trimmed)
                if (decoded == null) {
                    _sendError.value = "Invalid BOLT11 invoice"
                    return
                }
                if (decoded.isExpired()) {
                    _sendError.value = "Invoice has expired"
                    return
                }
                navigateTo(WalletPage.SendConfirm(
                    invoice = trimmed,
                    amountSats = decoded.amountSats,
                    paymentHash = decoded.paymentHash,
                    description = decoded.description
                ))
            }
            trimmed.contains("@") && trimmed.contains(".") -> {
                _sendAmount.value = ""
                navigateTo(WalletPage.SendAmount(trimmed))
            }
            else -> {
                _sendError.value = "Enter a lightning address (user@domain) or BOLT11 invoice"
            }
        }
    }

    fun resolveLightningAddress(address: String, amountSats: Long) {
        _isLoading.value = true
        accountScope.launch {
            try {
                val payInfo = Nip57.resolveLud16(address, httpClient)
                if (payInfo == null) {
                    _sendError.value = "Could not resolve lightning address"
                    _isLoading.value = false
                    return@launch
                }

                val amountMsats = amountSats * 1000
                if (amountMsats < payInfo.minSendable || amountMsats > payInfo.maxSendable) {
                    _sendError.value = "Amount out of range: ${payInfo.minSendable / 1000}-${payInfo.maxSendable / 1000} sats"
                    _isLoading.value = false
                    return@launch
                }

                val invoice = Nip57.fetchSimpleInvoice(payInfo.callback, amountMsats, httpClient)
                if (invoice == null) {
                    _sendError.value = "Failed to fetch invoice"
                    _isLoading.value = false
                    return@launch
                }

                val decoded = Bolt11.decode(invoice)
                navigateTo(WalletPage.SendConfirm(
                    invoice = invoice,
                    amountSats = decoded?.amountSats ?: amountSats,
                    paymentHash = decoded?.paymentHash,
                    description = decoded?.description ?: address
                ))
            } catch (e: Exception) {
                _sendError.value = e.message ?: "Failed to resolve address"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun payInvoice(invoice: String) {
        navigateTo(WalletPage.Sending(invoice))
        accountScope.launch {
            val result = activeProvider.payInvoice(invoice)

            result.fold(
                onSuccess = {
                    pageStack.removeAt(pageStack.lastIndex)
                    val resultPage = WalletPage.SendResult(true, "Payment sent!")
                    pageStack.add(resultPage)
                    _currentPage.value = resultPage
                    refreshBalance()
                },
                onFailure = { e ->
                    pageStack.removeAt(pageStack.lastIndex)
                    val resultPage = WalletPage.SendResult(false, e.message ?: "Payment failed")
                    pageStack.add(resultPage)
                    _currentPage.value = resultPage
                }
            )
        }
    }

    // --- Receive flow ---

    fun updateReceiveAmount(digit: Char) {
        val current = _receiveAmount.value
        if (current.length >= 12) return
        if (digit == '.') {
            if (current.contains('.')) return
            _receiveAmount.value = if (current.isEmpty()) "0." else "$current."
            return
        }
        _receiveAmount.value = current + digit
    }

    fun receiveAmountBackspace() {
        val current = _receiveAmount.value
        if (current.isNotEmpty()) {
            _receiveAmount.value = current.dropLast(1)
        }
    }

    fun setReceiveAmount(value: String) {
        _receiveAmount.value = value
    }

    fun generateInvoice(amountSats: Long, description: String = "", expirySecs: Int = 3600) {
        _isLoading.value = true
        accountScope.launch {
            val result = activeProvider.makeInvoice(amountSats * 1000, description, expirySecs)
            result.fold(
                onSuccess = { invoice ->
                    navigateTo(WalletPage.ReceiveInvoice(invoice, amountSats))
                },
                onFailure = { e ->
                    _sendError.value = e.message ?: "Failed to create invoice"
                }
            )
            _isLoading.value = false
        }
    }

    // --- Transactions ---

    fun loadTransactions() {
        _isLoading.value = true
        _transactionsError.value = null
        _hasMoreTransactions.value = true
        accountScope.launch {
            // Kick off zap receipt fetch in background (don't block)
            val zapJob = launch { fetchUserZapReceipts() }

            // Show transactions immediately using whatever zap data we already have cached
            val mapped = withContext(Dispatchers.IO) {
                val zapMaps = eventRepo.getZapReceiptCounterparties()
                val result = activeProvider.listTransactions()
                result.map { txs -> enrichTransactions(txs, zapMaps) }
            }
            mapped.fold(
                onSuccess = { txs ->
                    _transactions.value = dedupTransactions(txs)
                    _hasMoreTransactions.value = txs.size >= 50
                    requestMissingProfiles(txs)
                },
                onFailure = { e -> _transactionsError.value = e.message ?: "Failed to load transactions" }
            )
            _isLoading.value = false

            // After zap receipts arrive, re-enrich to pick up newly fetched counterparties
            zapJob.join()
            val current = _transactions.value
            if (current.isNotEmpty()) {
                val reEnriched = withContext(Dispatchers.IO) {
                    val zapMaps = eventRepo.getZapReceiptCounterparties()
                    enrichTransactions(current, zapMaps)
                }
                if (reEnriched != current) {
                    _transactions.value = dedupTransactions(reEnriched)
                    requestMissingProfiles(reEnriched)
                }
            }
        }
    }

    /**
     * Collapse exact backend duplicates and order transactions deterministically.
     *
     * A self-send surfaces as two records sharing one payment hash — one
     * outgoing, one incoming. Both legs must survive (we only drop repeats of
     * the same (paymentHash, type)), and on a timestamp tie the incoming
     * "received" leg sorts above its outgoing "sent" leg so the conclusion of
     * the payment reads on top. Mirrors iOS WalletStore.dedupTransactions.
     */
    private fun dedupTransactions(txs: List<WalletTransaction>): List<WalletTransaction> =
        txs.distinctBy { it.paymentHash to it.type }
            .sortedWith(
                compareByDescending<WalletTransaction> { it.settledAt ?: it.createdAt }
                    .thenBy { if (it.type == "incoming") 0 else 1 }
            )

    private fun enrichTransactions(
        txs: List<WalletTransaction>,
        zapMaps: EventRepository.ZapCounterpartyMaps
    ): List<WalletTransaction> {
        return txs.map { tx ->
            if (tx.counterpartyPubkey != null) tx
            else {
                val pubkey = extractCounterpartyPubkey(tx, zapMaps)
                if (pubkey != null && tx.type == "outgoing") {
                    ZapSender.persistRecipient(tx.paymentHash, pubkey)
                }
                tx.copy(counterpartyPubkey = pubkey)
            }
        }
    }

    private fun requestMissingProfiles(txs: List<WalletTransaction>) {
        val missing = txs.mapNotNull { it.counterpartyPubkey }
            .distinct()
            .filter { eventRepo.getProfileData(it) == null }
        missing.forEach { eventRepo.requestProfileIfMissing(it) }
        if (missing.isNotEmpty()) {
            accountScope.launch {
                delay(3_000)
                _profileRefreshKey.value++
            }
        }
    }

    fun loadMoreTransactions() {
        if (_isLoadingMore.value) return
        _isLoadingMore.value = true
        accountScope.launch {
            val currentSize = _transactions.value.size
            val mapped = withContext(Dispatchers.IO) {
                val zapMaps = eventRepo.getZapReceiptCounterparties()
                val result = activeProvider.listTransactions(limit = 50, offset = currentSize)
                result.map { txs ->
                    txs.map { tx ->
                        if (tx.counterpartyPubkey != null) tx
                        else {
                            val pubkey = extractCounterpartyPubkey(tx, zapMaps)
                            if (pubkey != null && tx.type == "outgoing") {
                                ZapSender.persistRecipient(tx.paymentHash, pubkey)
                            }
                            tx.copy(counterpartyPubkey = pubkey)
                        }
                    }
                }
            }
            mapped.fold(
                onSuccess = { txs ->
                    _transactions.value = dedupTransactions(_transactions.value + txs)
                    _hasMoreTransactions.value = txs.size >= 50
                    val missing = txs.mapNotNull { it.counterpartyPubkey }
                        .distinct()
                        .filter { eventRepo.getProfileData(it) == null }
                    missing.forEach { eventRepo.requestProfileIfMissing(it) }
                    if (missing.isNotEmpty()) {
                        accountScope.launch {
                            delay(3_000)
                            _profileRefreshKey.value++
                        }
                    }
                },
                onFailure = { /* silently ignore pagination errors */ }
            )
            _isLoadingMore.value = false
        }
    }

    /**
     * One-shot relay queries for kind 9735 zap receipts involving the current user.
     * Fetches both incoming (p tag = us) and outgoing (P tag = us) receipts.
     * Events auto-persist to ObjectBox via the normal relay event pipeline.
     */
    private suspend fun fetchUserZapReceipts() {
        val pubkey = keyRepo.getPubkeyHex() ?: run {
            Log.d("WalletBackup", "fetchUserZapReceipts: no pubkey")
            return
        }
        val ts = System.currentTimeMillis()

        // Incoming: receipts where we're the recipient (lowercase p tag)
        val inSubId = "zap-rcpt-in-$ts"
        val inFilter = Filter(kinds = listOf(9735), pTags = listOf(pubkey), limit = 1000)
        relayPool.sendToReadRelays(ClientMessage.req(inSubId, inFilter))

        // Outgoing: receipts where we're the sender (uppercase P tag)
        // Not all relays index #P, but those that do will return our outgoing zap receipts.
        val outSubId = "zap-rcpt-out-$ts"
        val outFilter = Filter(kinds = listOf(9735), bigPTags = listOf(pubkey), limit = 1000)
        relayPool.sendToReadRelays(ClientMessage.req(outSubId, outFilter))

        // Wait for EOSE from both in parallel
        coroutineScope {
            launch { withTimeoutOrNull(5_000) { relayPool.eoseSignals.first { it == inSubId } } }
            launch { withTimeoutOrNull(5_000) { relayPool.eoseSignals.first { it == outSubId } } }
        }
        relayPool.closeOnAllRelays(inSubId)
        relayPool.closeOnAllRelays(outSubId)
    }

    /**
     * Extract the counterparty pubkey from a transaction.
     * Uses persisted zap receipts (kind 9735) from ObjectBox — survives app restarts.
     * Falls back to ZapSender in-memory map and description parsing.
     */
    private fun extractCounterpartyPubkey(
        tx: WalletTransaction,
        zapMaps: EventRepository.ZapCounterpartyMaps
    ): String? {
        if (tx.type == "outgoing") {
            zapMaps.recipients[tx.paymentHash]?.let { return it }
            ZapSender.getZapRecipient(tx.paymentHash)?.let { return it }
        } else {
            zapMaps.senders[tx.paymentHash]?.let { return it }
        }

        // Fuzzy match by amount + timestamp (handles wrapped invoices)
        val bucket = tx.createdAt / 60
        for (off in -5L..5L) {
            val key = "${tx.amountMsats}:${bucket + off}"
            zapMaps.byAmountTime[key]?.let { (sender, recipient) ->
                if (tx.type == "outgoing" && recipient != null) return recipient
                if (tx.type == "incoming" && sender != null) return sender
            }
        }

        // Fallback: try parsing description as kind 9734 zap request
        val desc = tx.description ?: return null
        return try {
            val event = NostrEvent.fromJson(desc)
            if (event.kind != 9734) return null
            if (tx.type == "outgoing") {
                event.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)
            } else {
                event.pubkey
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getProfileData(pubkey: String) = eventRepo.getProfileData(pubkey)

    // --- Signing ---

    private fun buildSigner(): NostrSigner? {
        return when (keyRepo.getSigningMode()) {
            SigningMode.LOCAL -> {
                val keypair = keyRepo.getKeypair() ?: return null
                LocalSigner(keypair.privkey, keypair.pubkey)
            }
            SigningMode.READ_ONLY -> null
        }
    }

    companion object {
        /**
         * How long the setup flow waits for the verification round-trip before
         * declaring the connection dead. Long enough for a slow wallet on a cold
         * relay, short enough that a revoked or offline string alerts within a
         * few seconds instead of "succeeding" into a silently broken dashboard.
         */
        private const val NWC_VERIFY_TIMEOUT_MS = 6_000L

        /**
         * User-facing wording for a failed NWC setup verification. Public for
         * unit tests pinning the per-outcome copy, especially the
         * UNAUTHORIZED "may have been revoked" reading.
         */
        fun nwcSetupFailureMessage(outcome: NwcRepository.VerifyOutcome): String = when (outcome) {
            is NwcRepository.VerifyOutcome.Confirmed -> "Connected"
            is NwcRepository.VerifyOutcome.Refused -> when {
                outcome.code == "UNAUTHORIZED" ->
                    "The wallet rejected this connection — it may have been revoked. Create a new connection string in your wallet and try again."
                outcome.message != null -> "The wallet rejected the request: ${outcome.message}."
                else -> "The wallet rejected the request (${outcome.code})."
            }
            NwcRepository.VerifyOutcome.Unresponsive ->
                "No response from the wallet — the connection may have been revoked, or the wallet is offline."
        }
    }
}
