package com.wisp.app.ui.screen

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CameraAlt

import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.wisp.app.ui.theme.WispThemeColors
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.wisp.app.ui.component.generateQrBitmap
import com.wisp.app.R
import com.wisp.app.repo.BalanceUnit
import com.wisp.app.repo.WalletBalanceDisplayMode
import com.wisp.app.repo.FiatPreferences
import com.wisp.app.repo.WalletMode
import com.wisp.app.repo.WalletTransaction
import com.wisp.app.ui.component.NsecPasteGuard
import com.wisp.app.ui.component.SatsNumpad
import com.wisp.app.ui.util.AmountFormatter
import com.wisp.app.viewmodel.NwcRestoreState
import com.wisp.app.viewmodel.WalletPage
import com.wisp.app.viewmodel.WalletState
import com.wisp.app.viewmodel.WalletViewModel
import com.wisp.app.repo.TransactionStatus

// Full-screen wallet bottom sheets expand to the top of the window — push
// the grab handle below the status bar so it isn't clipped by device chrome.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletSheetDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        BottomSheetDefaults.DragHandle()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    viewModel: WalletViewModel,
    onBack: () -> Unit
) {
    val walletState by viewModel.walletState.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()

    // Always refresh wallet state when this screen appears
    LaunchedEffect(Unit) {
        viewModel.refreshState()
    }

    // Hide the wallet app bar on the Home dashboard — the bottom-nav wallet
    // tab is the entry point, and the dashboard's own top row (brand logo +
    // refresh + settings) plays the role of the toolbar. The mode picker
    // and the NWC setup screen render their own top-right Close pill
    // instead of an app bar to match iOS — leaves more headroom for the
    // centered logo + title layout.
    val hideAppBar = currentPage is WalletPage.Home ||
        currentPage is WalletPage.Transactions ||
        currentPage is WalletPage.ModeSelection ||
        currentPage is WalletPage.NwcSetup ||
        currentPage is WalletPage.SendInput ||
        currentPage is WalletPage.SendAmount ||
        currentPage is WalletPage.SendConfirm ||
        currentPage is WalletPage.Sending ||
        currentPage is WalletPage.SendResult ||
        currentPage is WalletPage.ReceiveAmount ||
        currentPage is WalletPage.ReceiveInvoice ||
        currentPage is WalletPage.ReceiveSuccess

    val isSendFlow = currentPage is WalletPage.SendInput ||
        currentPage is WalletPage.SendAmount ||
        currentPage is WalletPage.SendConfirm ||
        currentPage is WalletPage.Sending ||
        currentPage is WalletPage.SendResult
    val isReceiveFlow = currentPage is WalletPage.ReceiveAmount ||
        currentPage is WalletPage.ReceiveInvoice ||
        currentPage is WalletPage.ReceiveSuccess

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (!hideAppBar) {
                TopAppBar(
                    title = { Text(stringResource(R.string.title_wallet)) },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (!viewModel.navigateBack()) {
                                onBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        }
    ) { padding ->
        when (walletState) {
            is WalletState.NotConnected,
            is WalletState.Connecting,
            is WalletState.Error -> {
                if (currentPage !is WalletPage.NwcSetup) {
                    // Mode picker — must NOT live inside a verticalScroll, otherwise
                    // weighted spacers collapse and the row rides up to the top
                    // of the page instead of anchoring near the bottom.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp)
                    ) {
                        WalletModeSelectionContent(
                            onSelectNwc = { viewModel.selectNwcMode() }
                        )
                    }
                } else {
                    // The NWC setup screen renders with no TopAppBar, so the
                    // Column itself owns the status-bar inset to keep the
                    // top-right Close pill clear of the device chrome.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        WalletConnectionContent(
                            walletState = walletState,
                            connectionString = viewModel.connectionString.collectAsState().value,
                            statusLines = viewModel.statusLines.collectAsState().value,
                            nwcRestoreState = viewModel.nwcRestoreState.collectAsState().value,
                            onConnectionStringChange = { viewModel.updateConnectionString(it) },
                            onConnect = { viewModel.connectNwcWallet(verifySetup = true) },
                            onDisconnect = { viewModel.disconnectWallet() },
                            onRestoreFromBackup = { viewModel.restoreFromNwcBackup() },
                            onDismissRestore = { viewModel.dismissNwcRestore() },
                            onClose = { viewModel.navigateHome() }
                        )
                    }
                }
            }
            is WalletState.Connected -> {
                val balanceMsats = (walletState as WalletState.Connected).balanceMsats
                when (currentPage) {
                    is WalletPage.NwcExport -> {
                        val page = currentPage as WalletPage.NwcExport
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(horizontal = 16.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            NwcExportContent(
                                connectionString = page.connectionString,
                                onDone = { viewModel.navigateBack() }
                            )
                        }
                    }
                    is WalletPage.Home -> {
                        // Preload recent transactions for the inline footer.
                        LaunchedEffect(walletState) {
                            if (walletState is WalletState.Connected) viewModel.loadTransactions()
                        }
                        val profileKey = viewModel.profileRefreshKey.collectAsState().value
                        WalletHomeContent(
                            balanceMsats = balanceMsats,
                            balanceUnit = viewModel.balanceUnit.collectAsState().value,
                            onSend = { viewModel.navigateTo(WalletPage.SendInput) },
                            onReceive = {
                                viewModel.navigateTo(WalletPage.ReceiveAmount)
                            },
                            onTransactions = {
                                viewModel.loadTransactions()
                                viewModel.navigateTo(WalletPage.Transactions)
                            },
                            onRefresh = { viewModel.refreshBalance() },
                            onSettings = { viewModel.navigateTo(WalletPage.Settings) },
                            lightningAddress = viewModel.lightningAddress.collectAsState().value,
                            recentTransactions = viewModel.transactions.collectAsState().value,
                            profileLookup = remember(profileKey) { { viewModel.getProfileData(it) } },
                            nwcNodeAlias = viewModel.nwcNodeAlias.collectAsState().value,
                            pubkey = viewModel.keyRepo.getPubkeyHex(),
                            modifier = Modifier.padding(padding)
                        )
                    }
                    is WalletPage.ScanQR -> ScanQRContent(
                        onResult = { scanned ->
                            viewModel.updateSendInput(scanned)
                            viewModel.navigateTo(WalletPage.SendInput)
                        },
                        modifier = Modifier.padding(padding)
                    )
                    is WalletPage.SendInput,
                    is WalletPage.SendAmount,
                    is WalletPage.SendConfirm,
                    is WalletPage.Sending,
                    is WalletPage.SendResult -> {
                        // Home stays visible behind the send bottom sheet.
                        val profileKey = viewModel.profileRefreshKey.collectAsState().value
                        WalletHomeContent(
                            balanceMsats = balanceMsats,
                            balanceUnit = viewModel.balanceUnit.collectAsState().value,
                            onSend = {},
                            onReceive = { viewModel.navigateTo(WalletPage.ReceiveAmount) },
                            onTransactions = {
                                viewModel.loadTransactions()
                                viewModel.navigateTo(WalletPage.Transactions)
                            },
                            onRefresh = { viewModel.refreshBalance() },
                            onSettings = { viewModel.navigateTo(WalletPage.Settings) },
                            lightningAddress = viewModel.lightningAddress.collectAsState().value,
                            recentTransactions = viewModel.transactions.collectAsState().value,
                            profileLookup = remember(profileKey) { { viewModel.getProfileData(it) } },
                            nwcNodeAlias = viewModel.nwcNodeAlias.collectAsState().value,
                            pubkey = viewModel.keyRepo.getPubkeyHex(),
                            modifier = Modifier.padding(padding)
                        )
                    }
                    is WalletPage.ReceiveAmount,
                    is WalletPage.ReceiveInvoice,
                    is WalletPage.ReceiveSuccess -> {
                        // Home stays visible behind the receive bottom sheet.
                        val profileKey = viewModel.profileRefreshKey.collectAsState().value
                        WalletHomeContent(
                            balanceMsats = balanceMsats,
                            balanceUnit = viewModel.balanceUnit.collectAsState().value,
                            onSend = { viewModel.navigateTo(WalletPage.SendInput) },
                            onReceive = {},
                            onTransactions = {
                                viewModel.loadTransactions()
                                viewModel.navigateTo(WalletPage.Transactions)
                            },
                            onRefresh = { viewModel.refreshBalance() },
                            onSettings = { viewModel.navigateTo(WalletPage.Settings) },
                            lightningAddress = viewModel.lightningAddress.collectAsState().value,
                            recentTransactions = viewModel.transactions.collectAsState().value,
                            profileLookup = remember(profileKey) { { viewModel.getProfileData(it) } },
                            nwcNodeAlias = viewModel.nwcNodeAlias.collectAsState().value,
                            pubkey = viewModel.keyRepo.getPubkeyHex(),
                            modifier = Modifier.padding(padding)
                        )
                    }
                    is WalletPage.Transactions -> {
                        // Home stays visible behind the transactions bottom sheet.
                        val profileKey = viewModel.profileRefreshKey.collectAsState().value
                        WalletHomeContent(
                            balanceMsats = balanceMsats,
                            balanceUnit = viewModel.balanceUnit.collectAsState().value,
                            onSend = { viewModel.navigateTo(WalletPage.SendInput) },
                            onReceive = { viewModel.navigateTo(WalletPage.ReceiveAmount) },
                            onTransactions = {},
                            onRefresh = { viewModel.refreshBalance() },
                            onSettings = { viewModel.navigateTo(WalletPage.Settings) },
                            lightningAddress = viewModel.lightningAddress.collectAsState().value,
                            recentTransactions = viewModel.transactions.collectAsState().value,
                            profileLookup = remember(profileKey) { { viewModel.getProfileData(it) } },
                            nwcNodeAlias = viewModel.nwcNodeAlias.collectAsState().value,
                            pubkey = viewModel.keyRepo.getPubkeyHex(),
                            modifier = Modifier.padding(padding)
                        )
                    }
                    is WalletPage.Settings -> WalletSettingsContent(
                        walletMode = viewModel.walletMode.collectAsState().value,
                        balanceUnit = viewModel.balanceUnit.collectAsState().value,
                        onBalanceUnitChange = { viewModel.setBalanceUnit(it) },
                        lightningAddress = viewModel.lightningAddress.collectAsState().value,
                        onExportConnectionString = { viewModel.showNwcExport() },
                        onDeleteWallet = { viewModel.navigateTo(WalletPage.DeleteWalletConfirm) },
                        nwcNodeAlias = viewModel.nwcNodeAlias.collectAsState().value,
                        nwcConnectionInfo = viewModel.nwcConnectionInfo.collectAsState().value,
                        nwcSupportedMethods = viewModel.nwcSupportedMethods.collectAsState().value,
                        modifier = Modifier.padding(padding)
                    )
                    is WalletPage.DeleteWalletConfirm -> DeleteWalletConfirmContent(
                        onDelete = { viewModel.deleteWallet() },
                        onCancel = { viewModel.navigateBack() },
                        modifier = Modifier.padding(padding)
                    )
                    else -> {
                        // ModeSelection, NwcSetup — shouldn't appear while connected
                        val profileKey = viewModel.profileRefreshKey.collectAsState().value
                        WalletHomeContent(
                            balanceMsats = balanceMsats,
                            balanceUnit = viewModel.balanceUnit.collectAsState().value,
                            onSend = { viewModel.navigateTo(WalletPage.SendInput) },
                            onReceive = { viewModel.navigateTo(WalletPage.ReceiveAmount) },
                            onTransactions = {
                                viewModel.loadTransactions()
                                viewModel.navigateTo(WalletPage.Transactions)
                            },
                            onRefresh = { viewModel.refreshBalance() },
                            onSettings = { viewModel.navigateTo(WalletPage.Settings) },
                            lightningAddress = viewModel.lightningAddress.collectAsState().value,
                            recentTransactions = viewModel.transactions.collectAsState().value,
                            profileLookup = remember(profileKey) { { viewModel.getProfileData(it) } },
                            nwcNodeAlias = viewModel.nwcNodeAlias.collectAsState().value,
                            pubkey = viewModel.keyRepo.getPubkeyHex(),
                            modifier = Modifier.padding(padding)
                        )
                    }
                }

                // Transactions full-screen bottom sheet — swipe down to dismiss
                if (currentPage is WalletPage.Transactions) {
                    val txSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    val txProfileKey = viewModel.profileRefreshKey.collectAsState().value
                    ModalBottomSheet(
                        onDismissRequest = { viewModel.navigateBack() },
                        sheetState = txSheetState,
                        dragHandle = { WalletSheetDragHandle() }
                    ) {
                        TransactionHistoryContent(
                            transactions = viewModel.transactions.collectAsState().value,
                            error = viewModel.transactionsError.collectAsState().value,
                            isLoading = viewModel.isLoading.collectAsState().value,
                            isLoadingMore = viewModel.isLoadingMore.collectAsState().value,
                            hasMore = viewModel.hasMoreTransactions.collectAsState().value,
                            onLoadMore = { viewModel.loadMoreTransactions() },
                            profileLookup = { viewModel.getProfileData(it) },
                            profileRefreshKey = txProfileKey,
                            pubkey = viewModel.keyRepo.getPubkeyHex(),
                        )
                    }
                }

                // Send flow full-screen bottom sheet — swipe down to dismiss back to Home.
                if (isSendFlow) {
                    val sendSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    ModalBottomSheet(
                        onDismissRequest = { viewModel.navigateHome() },
                        sheetState = sendSheetState,
                        dragHandle = { WalletSheetDragHandle() }
                    ) {
                        when (val page = currentPage) {
                            is WalletPage.SendInput -> SendInputContent(
                                input = viewModel.sendInput.collectAsState().value,
                                error = viewModel.sendError.collectAsState().value,
                                onInputChange = { viewModel.updateSendInput(it) },
                                onNext = { viewModel.processInput() },
                                onScanQR = { viewModel.navigateTo(WalletPage.ScanQR) }
                            )
                            is WalletPage.SendAmount -> {
                                SendAmountContent(
                                    address = page.address,
                                    amount = viewModel.sendAmount.collectAsState().value,
                                    error = viewModel.sendError.collectAsState().value,
                                    isLoading = viewModel.isLoading.collectAsState().value,
                                    onDigit = { viewModel.updateSendAmount(it) },
                                    onBackspace = { viewModel.sendAmountBackspace() },
                                    onConfirm = {
                                        val sats = viewModel.sendAmount.value.toLongOrNull() ?: return@SendAmountContent
                                        viewModel.resolveLightningAddress(page.address, sats)
                                    }
                                )
                            }
                            is WalletPage.SendConfirm -> {
                                SendConfirmContent(
                                    invoice = page.invoice,
                                    amountSats = page.amountSats,
                                    description = page.description,
                                    networkName = "NWC",
                                    onPay = { viewModel.payInvoice(page.invoice) },
                                    onCancel = { viewModel.navigateBack() }
                                )
                            }
                            is WalletPage.Sending -> SendingContent()
                            is WalletPage.SendResult -> SendResultContent(
                                success = page.success,
                                message = page.message,
                                onDone = { viewModel.navigateHome() }
                            )
                            else -> {}
                        }
                    }
                }

                // Receive flow full-screen bottom sheet — swipe down to dismiss back to Home.
                if (isReceiveFlow) {
                    val receiveSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    ModalBottomSheet(
                        onDismissRequest = { viewModel.navigateHome() },
                        sheetState = receiveSheetState,
                        dragHandle = { WalletSheetDragHandle() }
                    ) {
                        when (val page = currentPage) {
                            is WalletPage.ReceiveAmount -> ReceiveAmountContent(
                                amount = viewModel.receiveAmount.collectAsState().value,
                                isLoading = viewModel.isLoading.collectAsState().value,
                                lightningAddress = viewModel.lightningAddress.collectAsState().value,
                                onAmountChange = { viewModel.setReceiveAmount(it) },
                                onGenerate = { sats, note, expirySecs -> viewModel.generateInvoice(sats, note, expirySecs) }
                            )
                            is WalletPage.ReceiveInvoice -> ReceiveInvoiceContent(
                                invoice = page.invoice,
                                amountSats = page.amountSats,
                                onDone = { viewModel.navigateHome() }
                            )
                            is WalletPage.ReceiveSuccess -> ReceiveSuccessContent(
                                amountSats = page.amountSats,
                                onDone = { viewModel.navigateHome() }
                            )
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

// --- Connection UI (not connected / connecting / error) ---

@Composable
private fun WalletConnectionContent(
    walletState: WalletState,
    connectionString: String,
    statusLines: List<String>,
    nwcRestoreState: NwcRestoreState = NwcRestoreState.Idle,
    onConnectionStringChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRestoreFromBackup: () -> Unit = {},
    onDismissRestore: () -> Unit = {},
    onClose: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val isConnecting = walletState is WalletState.Connecting
    var showScanner by remember { mutableStateOf(false) }

    // iOS 1:1 layout — top-right Close pill (no app bar), centered NWC
    // logo + title + subtitle, paste/scan card, helper text, full-width
    // Connect button. The Recommended wallets stack is removed: NWC
    // ecosystem links live on a separate discovery surface; this screen
    // is purely "paste your string and connect."
    val accent = WispThemeColors.zapColor

    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            modifier = Modifier.clickable(onClick = onClose),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = CircleShape
        ) {
            Text(
                stringResource(R.string.wallet_close),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_nwc_logo),
            contentDescription = null,
            modifier = Modifier.height(56.dp)
        )
    }
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.wallet_nwc_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.wallet_nwc_paste_prompt),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    )

    if (nwcRestoreState is NwcRestoreState.Searching) {
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.wallet_nwc_restore_searching),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (nwcRestoreState is NwcRestoreState.Found) {
        Spacer(Modifier.height(16.dp))
        val dateStr = remember(nwcRestoreState.createdAt) {
            val fmt = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
            fmt.format(java.util.Date(nwcRestoreState.createdAt * 1000L))
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = accent.copy(alpha = 0.16f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.wallet_nwc_restore_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.wallet_nwc_restore_body, dateStr),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onRestoreFromBackup,
                    enabled = !isConnecting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        stringResource(R.string.wallet_nwc_restore_action),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))

    // Paste / Scan card — top half displays the pasted string (or
    // stays empty while the user hasn't pasted anything); bottom row
    // splits a Paste button and a Scan QR button with a vertical
    // divider. Matches iOS 1:1 — no separate text field, no trailing
    // icon, no Recommended-wallets stack.
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .padding(14.dp)
            ) {
                if (connectionString.isBlank()) {
                    Text(
                        "nostr+walletconnect://…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                } else {
                    Text(
                        connectionString,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = !isConnecting) {
                            val pasted = clipboardManager.getText()?.text.orEmpty()
                            if (pasted.isNotBlank()
                                && !NsecPasteGuard.blockIfNsec(connectionString, pasted)
                            ) {
                                onConnectionStringChange(pasted.trim())
                            }
                        }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.wallet_paste),
                        style = MaterialTheme.typography.bodyMedium,
                        color = accent
                    )
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = !isConnecting) { showScanner = true }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.QrCode,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.wallet_scan_qr),
                        style = MaterialTheme.typography.bodyMedium,
                        color = accent
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp).padding(top = 2.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.wallet_nwc_connection_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(Modifier.height(8.dp))
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Outlined.CloudDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp).padding(top = 2.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.wallet_nwc_backup_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (walletState is WalletState.Error) {
        Spacer(Modifier.height(8.dp))
        Text(
            walletState.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }

    Spacer(Modifier.height(20.dp))

    Button(
        onClick = onConnect,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
        enabled = connectionString.isNotBlank() && !isConnecting
    ) {
        if (isConnecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.wallet_connecting))
        } else {
            Text(stringResource(R.string.wallet_connect))
        }
    }

    // Status-line stream (per-relay connect/relay-status output) is
    // intentionally suppressed on the NWC setup screen — the Connect
    // button's own spinner + "Connecting…" label covers the happy path,
    // and any failure surfaces via WalletState.Error above. Showing
    // verbose connect logs here just looked like noise (especially
    // after a one-tap backup restore).

    if (isConnecting) {
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.btn_cancel))
        }
    }

    Spacer(Modifier.height(32.dp))

    if (showScanner) {
        // Reuses the existing QrScanner dialog pattern from the send-
        // payment flow. Successful scan populates the connection
        // string field directly; user can then tap Connect.
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showScanner = false }
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.wallet_scan_qr_code),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))
                    com.wisp.app.ui.component.QrScanner(
                        onResult = { value ->
                            showScanner = false
                            onConnectionStringChange(value.trim())
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                        promptText = stringResource(R.string.wallet_point_camera)
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { showScanner = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            }
        }
    }
}

// --- Home screen ---

@Composable
private fun WalletHomeContent(
    balanceMsats: Long,
    balanceUnit: BalanceUnit = BalanceUnit.BITCOIN,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onTransactions: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    lightningAddress: String? = null,
    recentTransactions: List<WalletTransaction> = emptyList(),
    profileLookup: (String) -> com.wisp.app.nostr.ProfileData? = { null },
    nwcNodeAlias: String? = null,
    pubkey: String? = null,
    modifier: Modifier = Modifier
) {
    val balanceSats = balanceMsats / 1000
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("wisp_settings", android.content.Context.MODE_PRIVATE) }
    // Tri-state balance display (sats / fiat / hidden) — tap the
    // dashboard balance to cycle. Per-pubkey storage; migrates the
    // legacy global `balance_hidden` Bool on first read for a given
    // pubkey. iOS port of feat/wallet-balance-toggle (wisp-ios #166).
    var balanceDisplay by remember(pubkey) {
        mutableStateOf(WalletBalanceDisplayMode.read(prefs, pubkey))
    }
    val balanceHidden = balanceDisplay == WalletBalanceDisplayMode.HIDDEN
    val fiatPrefs = remember { FiatPreferences.get(context) }
    val fiatMode by fiatPrefs.fiatMode.collectAsState()
    val fiatCurrency by fiatPrefs.currency.collectAsState()
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val accent = WispThemeColors.zapColor

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Top bar ─────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Native NWC brand colors — no colorFilter so the
                // baked-in palette renders.
                Image(
                    painter = painterResource(R.drawable.ic_nwc_logo),
                    contentDescription = "NWC",
                    modifier = Modifier.height(22.dp)
                )
                if (!nwcNodeAlias.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        nwcNodeAlias,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row {
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh_balance),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // ── Balance ─────────────────────────────────────────────────
        // Tap to cycle sats → fiat → hidden. `fiat` is wallet-screen-
        // scoped: it renders the balance in the user's currently-set
        // fiat currency but does NOT flip the app-wide
        // [FiatPreferences.isFiatMode] flag (still respected by feed
        // counts / timestamps elsewhere).
        // Fixed-height container so the balance occupies the same vertical
        // space regardless of which mode is showing — keeps the lightning-
        // address pill + Send/Receive row anchored at a stable Y across the
        // sats/BTC/⚡/fiat/hidden cycle. One-line variants center vertically
        // within the reserved height; two-line variants (sats + subtitle,
        // hidden + "tap to reveal") fill it naturally.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .heightIn(min = 96.dp)
                .clickable {
                    balanceDisplay = balanceDisplay.next()
                    WalletBalanceDisplayMode.write(prefs, pubkey, balanceDisplay)
                }
        ) {
            when (balanceDisplay) {
                WalletBalanceDisplayMode.HIDDEN -> {
                    Text(
                        "* * * * *",
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.wallet_tap_to_reveal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                WalletBalanceDisplayMode.FIAT -> {
                    val fiat = AmountFormatter.formatFiat(balanceSats, fiatCurrency)
                    if (fiat != null) {
                        Text(
                            fiat,
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        // Exchange-rate cache hasn't loaded yet — fall
                        // back to the sats display so the dashboard
                        // doesn't show a blank or a placeholder.
                        Text(
                            "%,d".format(balanceSats),
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.wallet_sats),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                WalletBalanceDisplayMode.SATS -> {
                    // App-wide fiat mode still wins when the user has
                    // it on AND the wallet display is in its default
                    // (sats) state — same behaviour as before this
                    // tri-state landed.
                    if (fiatMode) {
                        Text(
                            AmountFormatter.formatShort(balanceSats, context),
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        // BalanceUnit selects between three sat denominations
                        // — SATS ("1,234" + "sats" subtitle), BITCOIN ("₿ 0.00001234"
                        // BTC-denominated), LIGHTNING (⚡ + sats inline).
                        // The setting lives in Wallet Settings → Display Unit.
                        when (balanceUnit) {
                            BalanceUnit.SATS -> {
                                Text(
                                    "%,d".format(balanceSats),
                                    style = MaterialTheme.typography.displayLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.wallet_sats),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            BalanceUnit.BITCOIN -> {
                                // Symbol rendered in onSurfaceVariant so the
                                // number stays the prominent reading — matches
                                // the muted "sats" subtitle in the SATS branch.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "₿",
                                        style = MaterialTheme.typography.displayLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "%,d".format(balanceSats),
                                        style = MaterialTheme.typography.displayLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            BalanceUnit.LIGHTNING -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_bolt),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.height(40.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "%,d".format(balanceSats),
                                        style = MaterialTheme.typography.displayLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Lightning address pill ─────────────────────────────────
        // Shown when the NWC URI carries a `lud16=...`, which
        // connectNwcWallet copies into `lightningAddress`. The old
        // NWC-only logo + "Nostr Wallet Connect" footer below the balance
        // was redundant (the dashboard header already brands the mode),
        // so it's removed.
        if (!lightningAddress.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.clickable {
                    clipboard.setPrimaryClip(ClipData.newPlainText("address", lightningAddress))
                },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(50)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bolt),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        lightningAddress,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // ── Send / Receive ─────────────────────────────────────────
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally)
        ) {
            WalletActionButton(
                icon = Icons.Default.ArrowUpward,
                label = stringResource(R.string.wallet_send),
                tint = accent,
                onClick = onSend
            )
            WalletActionButton(
                icon = Icons.Default.ArrowDownward,
                label = stringResource(R.string.wallet_receive),
                tint = accent,
                onClick = onReceive
            )
        }

        Spacer(Modifier.weight(1f))

        // ── Recent transactions inline footer ──────────────────────
        if (recentTransactions.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .pointerInput(onTransactions) {
                        var totalDrag = 0f
                        detectVerticalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onDragEnd = { totalDrag = 0f },
                            onDragCancel = { totalDrag = 0f }
                        ) { change, delta ->
                            totalDrag += delta
                            if (totalDrag < -40f) {
                                totalDrag = 0f
                                change.consume()
                                onTransactions()
                            }
                        }
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onTransactions)
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.wallet_recent).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.wallet_view_all),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
                // Match iOS — show recent transactions inline, "View all"
                // expands to the full screen. Row count scales with the
                // device's available height so a compact phone (e.g.
                // ~640dp tall) doesn't crowd out the balance + Send/
                // Receive controls, while bigger displays still get up
                // to 5 rows like iOS.
                val screenHeightDp = LocalConfiguration.current.screenHeightDp
                val txCount = when {
                    screenHeightDp >= 800 -> 5
                    screenHeightDp >= 720 -> 4
                    screenHeightDp >= 640 -> 3
                    else -> 2
                }
                recentTransactions.take(txCount).forEach { tx ->
                    TransactionRow(tx, profileLookup, balanceDisplay)
                }
            }
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun WalletActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(tint)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(28.dp),
                tint = Color.White
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- Send input ---

@Composable
private fun SendInputContent(
    input: String,
    error: String?,
    onInputChange: (String) -> Unit,
    onNext: () -> Unit,
    onScanQR: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Gallery image picker → decode QR from image
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
            val image = InputImage.fromBitmap(bitmap, 0)
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = BarcodeScanning.getClient(options)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val value = barcodes.firstOrNull()?.rawValue
                    if (value != null) {
                        // Strip common URI prefixes
                        val cleaned = value
                            .removePrefix("lightning:")
                            .removePrefix("LIGHTNING:")
                            .removePrefix("bitcoin:")
                            .removePrefix("BITCOIN:")
                        onInputChange(cleaned)
                    }
                }
                .addOnCompleteListener { scanner.close() }
        } catch (e: Exception) {
            Log.e("WalletScreen", "Failed to decode QR from image", e)
        }
    }

    val accent = WispThemeColors.zapColor
    val fieldShape = RoundedCornerShape(14.dp)
    val fieldBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val hint = stringResource(R.string.wallet_lightning_address_invoice)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.wallet_send),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .background(fieldBg, fieldShape)
                .padding(16.dp)
        ) {
            val entryStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            )
            BasicTextField(
                value = input,
                onValueChange = { new ->
                    if (!NsecPasteGuard.blockIfNsec(input, new)) onInputChange(new)
                },
                textStyle = entryStyle,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text(hint, style = entryStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                    inner()
                }
            )
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(fieldBg, fieldShape)
        ) {
            SendInputAction(
                icon = Icons.Default.QrCode,
                label = stringResource(R.string.wallet_scan_qr),
                onClick = onScanQR,
                modifier = Modifier.weight(1f)
            )
            VerticalDivider(modifier = Modifier.height(24.dp))
            SendInputAction(
                icon = Icons.Default.ContentPaste,
                label = stringResource(R.string.wallet_paste),
                onClick = {
                    clipboardManager.getText()?.text?.let { new ->
                        if (!NsecPasteGuard.blockIfNsec(input, new)) onInputChange(new)
                    }
                },
                modifier = Modifier.weight(1f)
            )
            VerticalDivider(modifier = Modifier.height(24.dp))
            SendInputAction(
                icon = Icons.Default.Image,
                label = stringResource(R.string.wallet_gallery),
                onClick = { galleryLauncher.launch("image/*") },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = fieldShape,
            enabled = input.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = Color.White,
                disabledContainerColor = fieldBg,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text(stringResource(R.string.wallet_next), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SendInputAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(contentColor = WispThemeColors.zapColor)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

// --- QR Scanner ---

@Composable
private fun ScanQRContent(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.wallet_scan_qr_code),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(16.dp))

        com.wisp.app.ui.component.QrScanner(
            onResult = { value ->
                val cleaned = value
                    .removePrefix("lightning:")
                    .removePrefix("LIGHTNING:")
                    .removePrefix("bitcoin:")
                    .removePrefix("BITCOIN:")
                onResult(cleaned)
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            promptText = stringResource(R.string.wallet_point_camera),
        )
    }
}

// --- Send amount (for lightning addresses) ---

@Composable
private fun SendAmountContent(
    address: String,
    amount: String,
    error: String?,
    isLoading: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.wallet_send_to),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            address,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.wallet_resolving), style = MaterialTheme.typography.bodyMedium)
        } else {
            SatsNumpad(
                amount = amount,
                onDigit = onDigit,
                onBackspace = onBackspace,
                onConfirm = onConfirm,
                confirmEnabled = amount.isNotEmpty() && (amount.toLongOrNull() ?: 0) > 0
            )
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// --- Send confirm ---

@Composable
private fun SendConfirmContent(
    invoice: String,
    amountSats: Long?,
    description: String?,
    networkName: String,
    onPay: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    // NWC has no fee quote before paying, so the total is the amount.
    val totalSats = amountSats
    val ctx = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            stringResource(R.string.wallet_confirm_payment),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(24.dp))

        if (amountSats != null) {
            Text(
                AmountFormatter.formatFull(amountSats, ctx),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(24.dp))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                // To
                SummaryRow(
                    label = stringResource(R.string.placeholder_to),
                    value = truncateInvoice(invoice)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Network
                SummaryRow(
                    label = stringResource(R.string.placeholder_network),
                    value = networkName
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Fees
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.wallet_fees),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "\u2014",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Total spent
                SummaryRow(
                    label = stringResource(R.string.wallet_total_spent),
                    value = if (totalSats != null) AmountFormatter.formatFull(totalSats, ctx) else amountSats?.let { AmountFormatter.formatFull(it, ctx) } ?: "\u2014",
                    bold = true
                )
            }
        }

        if (description != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onPay,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(painter = painterResource(R.drawable.ic_bolt), contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.wallet_pay))
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.btn_cancel))
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    bold: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun truncateInvoice(invoice: String): String {
    val lower = invoice.lowercase()
    return if (lower.length > 16) {
        lower.take(8) + "..." + lower.takeLast(6)
    } else {
        lower
    }
}

// --- Sending animation ---

@Composable
private fun SendingContent(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "sending")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_bolt),
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .scale(scale),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
        )
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.wallet_sending_payment),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- Send result ---

@Composable
private fun SendResultContent(
    success: Boolean,
    message: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    if (success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (success) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.White
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onDone) {
            Text(stringResource(R.string.btn_done))
        }
    }
}

// --- Receive amount ---

private enum class InvoiceExpiry(val seconds: Int) {
    ONE_HOUR(3600), ONE_DAY(86400), CUSTOM(0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiveAmountContent(
    amount: String,
    isLoading: Boolean,
    lightningAddress: String?,
    onAmountChange: (String) -> Unit,
    onGenerate: (Long, String, Int) -> Unit,
    onShowAddressQR: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val receiveCtx = LocalContext.current
    val fiatPrefs = remember { FiatPreferences.get(receiveCtx) }
    val fiatMode by fiatPrefs.fiatMode.collectAsState()
    val fiatCurrency by fiatPrefs.currency.collectAsState()
    val currency = remember(fiatCurrency) { com.wisp.app.repo.ExchangeRateRepository.currencyFor(fiatCurrency) }
    val btcPrice = com.wisp.app.repo.ExchangeRateRepository.rates.collectAsState().value[fiatCurrency.uppercase()]

    val fiatValue = if (fiatMode) amount.toDoubleOrNull() ?: 0.0 else 0.0
    val fiatSats: Long? = if (fiatMode && fiatValue > 0.0 && btcPrice != null && btcPrice > 0.0) {
        ((fiatValue / btcPrice) * 100_000_000.0).toLong()
    } else null
    val satAmount: Long? = if (fiatMode) fiatSats else amount.toLongOrNull()

    var description by remember { mutableStateOf("") }
    var showAddressQR by remember { mutableStateOf(false) }
    var selectedExpiry by remember { mutableStateOf(InvoiceExpiry.ONE_HOUR) }
    var customHours by remember { mutableStateOf("") }
    val expirySecs = when (selectedExpiry) {
        InvoiceExpiry.ONE_HOUR -> 3600
        InvoiceExpiry.ONE_DAY -> 86400
        InvoiceExpiry.CUSTOM -> (customHours.toIntOrNull() ?: 0) * 3600
    }
    val canCreate = (satAmount ?: 0L) > 0L && !isLoading &&
        (selectedExpiry != InvoiceExpiry.CUSTOM || expirySecs > 0)

    val fieldShape = RoundedCornerShape(14.dp)
    val fieldBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val scrollState = rememberScrollState()
    var invoiceGenerating by remember { mutableStateOf(false) }

    // Reset local generating flag once the ViewModel finishes
    LaunchedEffect(isLoading) { if (!isLoading) invoiceGenerating = false }

    // Scroll after AnimatedVisibility has expanded and laid out
    LaunchedEffect(showAddressQR) {
        if (showAddressQR) {
            kotlinx.coroutines.delay(350)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(scrollState)
    ) {
        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.wallet_receive),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))

        // AMOUNT field
        Text(
            stringResource(R.string.wallet_receive_amount_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(fieldBg, fieldShape)
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            val amountTextStyle = TextStyle(
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            BasicTextField(
                value = amount,
                onValueChange = { input ->
                    val filtered = if (fiatMode) {
                        val sb = StringBuilder()
                        var seenDot = false
                        for (c in input) {
                            if (c.isDigit()) sb.append(c)
                            else if (c == '.' && !seenDot) { sb.append(c); seenDot = true }
                        }
                        sb.toString()
                    } else {
                        input.filter { it.isDigit() }
                    }
                    onAmountChange(filtered)
                },
                textStyle = amountTextStyle,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (fiatMode) KeyboardType.Decimal else KeyboardType.NumberPassword
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (amount.isEmpty()) {
                            Text(
                                "0",
                                style = amountTextStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                        inner()
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (fiatMode) currency.code else stringResource(R.string.wallet_sats),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (fiatMode && fiatSats != null && fiatSats > 0L) {
            Spacer(Modifier.height(6.dp))
            Text(
                "≈ %,d sats".format(fiatSats),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        // NOTE field
        Text(
            stringResource(R.string.wallet_receive_note_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(fieldBg, fieldShape)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            val noteStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            )
            BasicTextField(
                value = description,
                onValueChange = { description = it },
                textStyle = noteStyle,
                singleLine = true,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (description.isEmpty()) {
                        Text(
                            stringResource(R.string.wallet_receive_note_placeholder),
                            style = noteStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                    inner()
                }
            )
        }

        Spacer(Modifier.height(20.dp))

        // EXPIRES selector
        Text(
            stringResource(R.string.wallet_receive_expires_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                InvoiceExpiry.ONE_HOUR to stringResource(R.string.wallet_receive_expires_1h),
                InvoiceExpiry.ONE_DAY to stringResource(R.string.wallet_receive_expires_24h),
                InvoiceExpiry.CUSTOM to stringResource(R.string.wallet_receive_expires_custom)
            ).forEach { (expiry, label) ->
                val isSelected = selectedExpiry == expiry
                OutlinedButton(
                    onClick = { selectedExpiry = expiry },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) { Text(label, style = MaterialTheme.typography.labelMedium) }
            }
        }
        if (selectedExpiry == InvoiceExpiry.CUSTOM) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = customHours,
                onValueChange = { customHours = it.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.wallet_receive_expires_hours)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(24.dp))

        if (invoiceGenerating) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(fieldBg, fieldShape)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.wallet_creating_invoice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Button(
                onClick = {
                    val sats = satAmount ?: return@Button
                    invoiceGenerating = true
                    onGenerate(sats, description, expirySecs)
                },
                enabled = canCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = fieldShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    disabledContainerColor = fieldBg,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(
                    stringResource(R.string.wallet_receive_create_invoice),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Lightning address — shown below invoice form as "or receive via" row
        if (!lightningAddress.isNullOrBlank()) {
            Spacer(Modifier.height(24.dp))
            LightningAddressReceiveRow(
                address = lightningAddress,
                onShowQR = { showAddressQR = !showAddressQR }
            )
            AnimatedVisibility(visible = showAddressQR) {
                val clipboardManager = LocalClipboardManager.current
                val qrBitmap = remember(lightningAddress) {
                    val writer = QRCodeWriter()
                    val matrix = writer.encode(lightningAddress, BarcodeFormat.QR_CODE, 512, 512)
                    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
                    for (x in 0 until matrix.width) {
                        for (y in 0 until matrix.height) {
                            bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                        }
                    }
                    bmp
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(20.dp)
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_invoice_qr_code),
                        modifier = Modifier
                            .size(240.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            lightningAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(lightningAddress)) }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.wallet_copy_address),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LightningAddressReceiveRow(
    address: String,
    onShowQR: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.wallet_receive_via_address),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(14.dp)
                )
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Icon(
                Icons.Outlined.Bolt,
                contentDescription = null,
                tint = WispThemeColors.zapColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                address,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onShowQR) {
                Icon(
                    Icons.Default.QrCode,
                    contentDescription = stringResource(R.string.wallet_receive_address_tab),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { clipboardManager.setText(AnnotatedString(address)) }) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.cd_copy_invoice),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// --- Receive invoice (QR code) ---

@Composable
private fun ReceiveInvoiceContent(
    invoice: String,
    amountSats: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val ctx = LocalContext.current

    val qrBitmap = remember(invoice) {
        val writer = QRCodeWriter()
        val data = "lightning:$invoice"
        val matrix = writer.encode(data, BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            AmountFormatter.formatFull(amountSats, ctx),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(24.dp))

        Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.cd_invoice_qr_code),
            modifier = Modifier
                .size(280.dp)
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(12.dp)
        )

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    invoice,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(invoice))
                }, colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_invoice),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        FilledTonalButton(onClick = onDone) {
            Text(stringResource(R.string.btn_done))
        }

        Spacer(Modifier.height(32.dp))
    }
}

// --- Receive Success ---

@Composable
private fun ReceiveSuccessContent(
    amountSats: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val ctx = LocalContext.current

    // Animation progress: 0 → 1 over ~1.6s
    val animProgress = remember { Animatable(0f) }
    // Checkmark fade-in after rings finish
    val checkAlpha = remember { Animatable(0f) }
    // Text + button fade-in
    val contentAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Phase 1: Rings radiate out (0 → 1)
        animProgress.animateTo(1f, tween(1600, easing = LinearEasing))
        // Phase 2: Checkmark fades in
        checkAlpha.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
        // Phase 3: Text and button
        contentAlpha.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Ring animation + checkmark occupy the same centered space
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(200.dp)
        ) {
            // Concentric rings radiating outward
            Canvas(modifier = Modifier.size(200.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val maxRadius = size.width / 2f

                // 4 rings, staggered in time like the wisp logo layers
                val ringCount = 4
                for (i in 0 until ringCount) {
                    // Each ring starts at a staggered offset
                    val stagger = i * 0.15f
                    val ringProgress = ((animProgress.value - stagger) / (1f - stagger))
                        .coerceIn(0f, 1f)

                    if (ringProgress <= 0f) continue

                    // Ring expands from core (10%) to full radius
                    val radius = maxRadius * (0.1f + ringProgress * 0.9f)

                    // Fade: appear, peak, then fade out
                    val alpha = if (ringProgress < 0.3f) {
                        ringProgress / 0.3f  // fade in
                    } else {
                        1f - ((ringProgress - 0.3f) / 0.7f)  // fade out
                    }.coerceIn(0f, 1f)

                    // Outer rings are thinner and more transparent
                    val baseAlpha = 1f - (i * 0.2f)
                    val strokeWidth = (4f - i * 0.6f).coerceAtLeast(1.5f)

                    drawCircle(
                        color = primary.copy(alpha = alpha * baseAlpha * 0.8f),
                        radius = radius,
                        center = androidx.compose.ui.geometry.Offset(cx, cy),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = strokeWidth * density
                        )
                    )
                }

                // Glowing core dot that shrinks as rings expand
                val coreAlpha = (1f - animProgress.value).coerceIn(0f, 1f)
                if (coreAlpha > 0f) {
                    // Outer glow
                    drawCircle(
                        color = primary.copy(alpha = coreAlpha * 0.3f),
                        radius = 24f,
                        center = androidx.compose.ui.geometry.Offset(cx, cy)
                    )
                    // Bright core
                    drawCircle(
                        color = primary.copy(alpha = coreAlpha),
                        radius = 10f,
                        center = androidx.compose.ui.geometry.Offset(cx, cy)
                    )
                    // Hot center
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = coreAlpha),
                        radius = 4f,
                        center = androidx.compose.ui.geometry.Offset(cx, cy)
                    )
                }
            }

            // Checkmark circle fades in at the same center position
            if (checkAlpha.value > 0f) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            primary.copy(alpha = checkAlpha.value * 0.15f),
                            CircleShape
                        )
                        .padding(12.dp),
                    tint = primary.copy(alpha = checkAlpha.value)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            stringResource(R.string.wallet_payment_received),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha.value)
        )

        Spacer(Modifier.height(8.dp))

        Text(
            AmountFormatter.formatFull(amountSats, ctx),
            style = MaterialTheme.typography.headlineMedium,
            color = primary.copy(alpha = contentAlpha.value)
        )

        Spacer(Modifier.height(32.dp))

        if (contentAlpha.value > 0.5f) {
            Button(onClick = onDone) {
                Text(stringResource(R.string.btn_done))
            }
        }
    }
}

// --- Transaction History ---

@Composable
@Suppress("UNUSED_PARAMETER")
private fun TransactionHistoryContent(
    transactions: List<WalletTransaction>,
    error: String?,
    isLoading: Boolean,
    isLoadingMore: Boolean = false,
    hasMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    profileLookup: (String) -> com.wisp.app.nostr.ProfileData?,
    profileRefreshKey: Int = 0,
    pubkey: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("wisp_settings", android.content.Context.MODE_PRIVATE) }
    // Mirror the dashboard's tri-state display mode on tx rows so a
    // HIDDEN state masks both the dashboard balance AND every per-row
    // amount + fee. iOS port keeps these in lockstep via the same
    // per-pubkey storage key (`walletBalanceDisplay_<pubkey>`).
    val displayMode = remember(pubkey) { WalletBalanceDisplayMode.read(prefs, pubkey) }
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        Text(
            stringResource(R.string.wallet_transactions),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
        )

        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (error.contains("NOT_IMPLEMENTED", ignoreCase = true) ||
                            error.contains("not supported", ignoreCase = true))
                            stringResource(R.string.wallet_not_supported)
                        else error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            transactions.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.wallet_no_transactions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn {
                    // Key by paymentHash + direction so a self-send's two legs
                    // (same hash, opposite type) keep distinct identities and
                    // both render. The list is deduped by (paymentHash, type)
                    // upstream, so these keys are unique.
                    items(transactions, key = { "${it.paymentHash}|${it.type}" }) { tx ->
                        TransactionRow(tx, profileLookup, displayMode, expandable = true)
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    if (hasMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    TextButton(onClick = onLoadMore) {
                                        Text(stringResource(R.string.wallet_load_more))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(
    tx: WalletTransaction,
    profileLookup: (String) -> com.wisp.app.nostr.ProfileData?,
    displayMode: WalletBalanceDisplayMode = WalletBalanceDisplayMode.SATS,
    expandable: Boolean = false
) {
    val isIncoming = tx.type == "incoming"
    val amountSats = tx.amountMsats / 1000
    val profile = tx.counterpartyPubkey?.let { profileLookup(it) }
    val ctx = LocalContext.current
    val fiatMode by FiatPreferences.get(ctx).fiatMode.collectAsState()
    val fiatCurrency by FiatPreferences.get(ctx).currency.collectAsState()
    val isHidden = displayMode == WalletBalanceDisplayMode.HIDDEN
    val isWalletFiat = displayMode == WalletBalanceDisplayMode.FIAT
    val clipboardManager = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar or direction icon
            if (profile?.picture != null) {
                AsyncImage(
                    model = profile.picture,
                    contentDescription = profile.displayString,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (isIncoming) Color(0xFF2E7D32).copy(alpha = 0.1f)
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isIncoming) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                        contentDescription = if (isIncoming) stringResource(R.string.wallet_received) else stringResource(R.string.wallet_sent),
                        tint = if (isIncoming) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Name/description + time
            Column(modifier = Modifier.weight(1f)) {
                val displayLabel = if (profile != null) {
                    profile.displayString
                } else {
                    // Try to get a meaningful description; skip raw zap request JSON
                    val desc = tx.description?.takeIf {
                        it.isNotBlank() && it != "null" && !it.trimStart().startsWith("{")
                    }
                    desc ?: if (isIncoming) stringResource(R.string.wallet_received) else stringResource(R.string.wallet_sent)
                }
                Text(
                    displayLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A failed payment needs its own label: without one it
                    // reads as an ordinary completed row, i.e. as sats spent.
                    val statusLabel = when (tx.status) {
                        TransactionStatus.PENDING -> stringResource(R.string.wallet_tx_pending)
                        TransactionStatus.FAILED -> stringResource(R.string.wallet_tx_failed)
                        TransactionStatus.COMPLETED -> null
                    }
                    if (statusLabel != null) {
                        Text(
                            statusLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (tx.failed) MaterialTheme.colorScheme.error
                            else WispThemeColors.zapColor
                        )
                        Text(
                            " · ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        formatRelativeTime(tx.settledAt ?: tx.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Amount + fee. In HIDDEN mode every number is masked so a
            // "show my wallet without showing the numbers" screenshot
            // works. Wallet-screen FIAT mode renders amounts in the user's
            // selected fiat currency without flipping the app-wide flag.
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val sign = if (isIncoming) "+" else "-"
                    // Green means money arrived and red means it left.
                    val signColor = if (isIncoming) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                    when {
                        isHidden -> Text(
                            "$sign* * *",
                            style = MaterialTheme.typography.titleMedium,
                            color = signColor
                        )
                        isWalletFiat -> {
                            val fiat = AmountFormatter.formatFiat(amountSats, fiatCurrency)
                            if (fiat != null) {
                                Text(
                                    "$sign$fiat",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = signColor
                                )
                            } else {
                                Text(
                                    "$sign%,d".format(amountSats),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = signColor
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.wallet_sats),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        fiatMode -> Text(
                            "$sign${AmountFormatter.formatFull(amountSats, ctx)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = signColor
                        )
                        else -> {
                            Text(
                                "$sign%,d".format(amountSats),
                                style = MaterialTheme.typography.titleMedium,
                                color = signColor
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.wallet_sats),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (!isIncoming && tx.feeMsats > 0) {
                    val feeSats = tx.feeMsats / 1000
                    val feeText = when {
                        isHidden -> stringResource(R.string.wallet_fee, 0).replace("0", "***")
                        isWalletFiat -> {
                            val fiat = AmountFormatter.formatFiat(feeSats, fiatCurrency)
                            if (fiat != null) stringResource(R.string.wallet_fee_money, fiat)
                            else stringResource(R.string.wallet_fee, feeSats)
                        }
                        fiatMode -> stringResource(R.string.wallet_fee_money, AmountFormatter.formatFull(feeSats, ctx))
                        else -> stringResource(R.string.wallet_fee, feeSats)
                    }
                    Text(
                        feeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (expandable) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (expandable) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                TransactionDetailPanel(tx = tx, onCopy = { clipboardManager.setText(AnnotatedString(it)) })
            }
        }
    }
}

@Composable
private fun TransactionDetailPanel(tx: WalletTransaction, onCopy: (String) -> Unit) {
    val ctx = LocalContext.current
    val amountSats = tx.amountMsats / 1000
    val feeSats = tx.feeMsats / 1000
    val dateStr = java.text.SimpleDateFormat("MMM d, yyyy HH:mm", java.util.Locale.US)
        .format(java.util.Date((tx.settledAt ?: tx.createdAt) * 1000L))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(2.dp))

        TxDetailRow(
            "Status",
            when (tx.status) {
                TransactionStatus.PENDING -> "Pending"
                TransactionStatus.COMPLETED -> "Completed"
                TransactionStatus.FAILED -> "Failed — not sent"
            }
        )
        TxDetailRow("Type", "Lightning")
        TxDetailRow("Amount", "%,d sats".format(amountSats))
        if (feeSats > 0) TxDetailRow("Network fee", "%,d sats".format(feeSats))
        TxDetailRow("Date", dateStr)
        if (!tx.description.isNullOrBlank() && !tx.description.trimStart().startsWith("{")) {
            TxDetailRow("Note", tx.description)
        }
        TxDetailRow("Payment hash", tx.paymentHash, mono = true, onCopy = { onCopy(tx.paymentHash) })

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun TxDetailRow(
    label: String,
    value: String,
    mono: Boolean = false,
    onCopy: (() -> Unit)? = null
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(110.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (onCopy != null) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "Copy",
                modifier = Modifier
                    .size(14.dp)
                    .clickable(onClick = onCopy),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// --- Wallet Mode Selection ---

@Composable
private fun WalletModeSelectionContent(
    onSelectNwc: () -> Unit
) {
    val accent = WispThemeColors.zapColor

    // Use a Column with weighted spacers so logo+copy sit upper-middle
    // and the NWC row is bottom-anchored — mirrors the iOS layout.
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))

        // Logo + title + subtitle
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .background(accent, CircleShape)
            ) {
                Icon(
                    Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.wallet_connect_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.wallet_connect_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        Spacer(Modifier.weight(2f))

        // Wallet connection row
        WalletModeRow(
            leadingIcon = {
                Image(
                    painter = painterResource(R.drawable.ic_nwc_logo),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = stringResource(R.string.wallet_nwc_title),
            subtitle = stringResource(R.string.wallet_nwc_subtitle),
            highlighted = true,
            onClick = onSelectNwc
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WalletModeRow(
    leadingIcon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    highlighted: Boolean = false
) {
    val accent = WispThemeColors.zapColor
    val shape = RoundedCornerShape(14.dp)
    val containerColor = if (highlighted) accent else MaterialTheme.colorScheme.surfaceVariant
    val titleColor = if (highlighted) Color.White else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (highlighted) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    val arrowColor = if (highlighted) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    // Layered shadows produce the iOS soft-glow halo: wide outer bleed +
    // tighter inner glow, both tinted with the accent. Colored shadows
    // only honor `ambientColor`/`spotColor` on API 28+; below that the
    // device falls back to the system grey shadow.
    val highlightModifier = if (highlighted) {
        Modifier
            .shadow(elevation = 24.dp, shape = shape, ambientColor = accent, spotColor = accent)
            .shadow(elevation = 10.dp, shape = shape, ambientColor = accent, spotColor = accent)
    } else Modifier
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(highlightModifier)
            .clickable(onClick = onClick),
        color = containerColor,
        shape = shape
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(36.dp)
            ) { leadingIcon() }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = arrowColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// --- NWC connection string export ---

@Composable
private fun NwcExportContent(
    connectionString: String,
    onDone: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    val zapColor = com.wisp.app.ui.theme.WispThemeColors.zapColor

    Spacer(Modifier.height(16.dp))

    Text(
        "Connection String",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(8.dp))

    // Warning card — mirrors wisp-ios NwcConnectionStringView.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = zapColor.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = zapColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Keep this string secret",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Anyone with this connection string can spend from your wallet, up to whatever limits your wallet provider set. Only paste it into apps you trust.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Spacer(Modifier.height(24.dp))

    if (revealed) {
        // The raw URI, shown sharp once revealed.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                connectionString,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(16.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // Copy + Hide (mirrors iOS: only offered once revealed).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(connectionString))
                    copied = true
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (copied) "Copied ✓" else "Copy")
            }
            OutlinedButton(
                onClick = {
                    revealed = false
                    showQr = false
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Hide")
            }
        }

        Spacer(Modifier.height(12.dp))

        // Show / Hide QR code toggle.
        OutlinedButton(
            onClick = { showQr = !showQr },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.QrCode,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(if (showQr) "Hide QR code" else "Show QR code")
        }

        if (showQr) {
            Spacer(Modifier.height(12.dp))
            // NWC URIs are long, so error correction stays at M — higher levels
            // push the module density past what on-screen scanning handles.
            val qrBitmap = remember(connectionString) {
                generateQrBitmap(connectionString, errorCorrection = ErrorCorrectionLevel.M)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Connection string QR code",
                        modifier = Modifier.size(240.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Scan from another wallet-connect app to import this connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        // Tap to reveal — the string stays hidden until an explicit tap.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { revealed = true },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Outlined.Visibility,
                    contentDescription = "Reveal connection string",
                    modifier = Modifier.size(32.dp),
                    tint = zapColor
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap to reveal",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    Spacer(Modifier.height(24.dp))

    Button(
        onClick = onDone,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Done")
    }

    Spacer(Modifier.height(32.dp))
}

// --- Wallet Settings ---

@Composable
private fun WalletSettingsContent(
    walletMode: WalletMode,
    balanceUnit: BalanceUnit = BalanceUnit.BITCOIN,
    onBalanceUnitChange: (BalanceUnit) -> Unit = {},
    lightningAddress: String?,
    onExportConnectionString: () -> Unit = {},
    onDeleteWallet: () -> Unit,
    nwcNodeAlias: String? = null,
    nwcConnectionInfo: com.wisp.app.repo.NwcRepository.ConnectionInfo? = null,
    nwcSupportedMethods: List<String> = emptyList(),
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val settingsCtx = LocalContext.current
    val fiatMode by FiatPreferences.get(settingsCtx).fiatMode.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        // ── Wallet Info expandable ─────────────────
        Spacer(Modifier.height(24.dp))
        Text(
            if (walletMode == WalletMode.NWC) "Wallet Connection" else "Wallet Info",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))
        WalletInfoCard(
            nwcNodeAlias = nwcNodeAlias,
            nwcConnectionInfo = nwcConnectionInfo,
            nwcSupportedMethods = nwcSupportedMethods,
            lightningAddress = lightningAddress,
            onCopy = { value ->
                clipboardManager.setText(AnnotatedString(value))
            }
        )

        // Display section — only relevant when showing sats
        if (!fiatMode) {
            Spacer(Modifier.height(24.dp))

            Text(
                "Display",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "Balance Unit",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
            val units = listOf(BalanceUnit.SATS, BalanceUnit.BITCOIN, BalanceUnit.LIGHTNING)
            units.forEach { unit ->
                val selected = balanceUnit == unit
                val tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                OutlinedButton(
                    onClick = { onBalanceUnitChange(unit) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    when (unit) {
                        BalanceUnit.BITCOIN -> Text(
                            "\u20BF 1,000",
                            color = tint,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                        BalanceUnit.LIGHTNING -> {
                            Icon(
                                painter = painterResource(R.drawable.ic_bolt),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.height(14.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text("1,000", color = tint, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        BalanceUnit.SATS -> Text(
                            "1,000 sats",
                            color = tint,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                }
            }
            }
        }

        // Security section
        Spacer(Modifier.height(24.dp))

        Text(
            "Security",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(12.dp))

        if (walletMode == WalletMode.NWC) {
            val zapColor = com.wisp.app.ui.theme.WispThemeColors.zapColor
            Card(
                onClick = onExportConnectionString,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.VpnKey,
                        contentDescription = null,
                        tint = zapColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Connection string",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "View, copy, or scan to move this wallet to another app",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Disclaimer
        Spacer(Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Text(
                "IMPORTANT: Pinket never holds user funds. You manage your own wallet and are responsible for securing it properly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }

        Spacer(Modifier.height(32.dp))

        // Disconnecting NWC is a quiet, recoverable affordance (the
        // connection string lives in the external wallet): a card row
        // with red text + swap icon plus a caption, matching iOS.
        run {
            Text(
                stringResource(R.string.wallet_disconnect_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 6.dp)
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDeleteWallet),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = Color(0xFFFF3B30),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.wallet_switch_wallet),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFFFF3B30)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.wallet_switch_wallet_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // Footer
        Spacer(Modifier.height(32.dp))

        run {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_nwc_logo),
                    contentDescription = "NWC",
                    modifier = Modifier.height(18.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.wallet_nwc_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// --- Delete Wallet Confirmation ---

@Composable
private fun DeleteWalletConfirmContent(
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        // Same iOS-red `#FF3B30` as the entry-point card in settings.
        val confirmAccent = Color(0xFFFF3B30)
        Icon(
            Icons.Default.Close,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .background(confirmAccent.copy(alpha = 0.1f), CircleShape)
                .padding(16.dp),
            tint = confirmAccent
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "Disconnect NWC",
            style = MaterialTheme.typography.headlineMedium,
            color = confirmAccent
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "This will remove the NWC connection string from this device. You can reconnect anytime with a new connection string.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = confirmAccent,
                contentColor = Color.White
            )
        ) {
            Text("Disconnect")
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel")
        }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Expandable card surfacing the active wallet's metadata. Collapsed
 * shows just the brand mark (+ alias on NWC); tap expands to reveal
 * the per-mode detail rows.
 */
@Composable
private fun WalletInfoCard(
    nwcNodeAlias: String?,
    nwcConnectionInfo: com.wisp.app.repo.NwcRepository.ConnectionInfo?,
    nwcSupportedMethods: List<String>,
    lightningAddress: String?,
    onCopy: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // NWC: native brand colors (no tint).
                Image(
                    painter = painterResource(R.drawable.ic_nwc_logo),
                    contentDescription = "NWC",
                    modifier = Modifier.height(22.dp)
                )
                if (!nwcNodeAlias.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        nwcNodeAlias,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    nwcConnectionInfo?.let { info ->
                        WalletInfoRow("Service pubkey", truncateMiddle(info.servicePubkeyHex),
                            onCopy = { onCopy(info.servicePubkeyHex) })
                        WalletInfoRow("Client pubkey", truncateMiddle(info.clientPubkeyHex),
                            onCopy = { onCopy(info.clientPubkeyHex) })
                        WalletInfoRow("Relay", info.relayUrl,
                            onCopy = { onCopy(info.relayUrl) })
                        WalletInfoRow("Encryption", info.encryption)
                    }
                    if (!lightningAddress.isNullOrBlank()) {
                        WalletInfoRow("Lightning address", lightningAddress,
                            onCopy = { onCopy(lightningAddress) })
                    }
                    if (nwcSupportedMethods.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Supported methods",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowMethodChips(nwcSupportedMethods)
                    }
                }
            }
        }
    }
}

@Composable
private fun WalletInfoRow(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Fixed-width label column so values across rows align to the
        // same x-coordinate regardless of label length, and the value
        // text always has a stable container to ellipsize within.
        // Without this, "Lightning address" pushes its value column 30dp
        // to the right of "Relay" — the iOS settings panel uses the
        // same column-aligned layout.
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(min = 110.dp)
                .padding(end = 12.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
        if (onCopy != null) {
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowMethodChips(methods: List<String>) {
    // Simple wrapping row using Compose's FlowRow.
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        methods.forEach { method ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ) {
                Text(
                    method,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

private fun truncateMiddle(value: String, head: Int = 8, tail: Int = 8): String {
    if (value.length <= head + tail + 1) return value
    return "${value.take(head)}…${value.takeLast(tail)}"
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - timestamp
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        diff < 604800 -> "${diff / 86400}d ago"
        else -> {
            val date = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            date.format(java.util.Date(timestamp * 1000))
        }
    }
}
