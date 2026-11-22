package com.wisp.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wisp.app.R
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.toNpub
import com.wisp.app.repo.ExchangeRateRepository
import com.wisp.app.repo.FiatCurrency
import com.wisp.app.repo.FiatPreferences
import com.wisp.app.repo.PayMethod
import com.wisp.app.repo.PayOptions
import com.wisp.app.repo.ZapPreferences
import com.wisp.app.repo.ZapPreset
import com.wisp.app.ui.theme.WispThemeColors
import com.wisp.app.ui.theme.inkOrWhite
import com.wisp.app.ui.util.AmountFormatter
import com.wisp.app.util.Monero
import com.wisp.app.util.MoneroWallet
import java.math.BigDecimal
import kotlinx.coroutines.launch

/**
 * Zap composer — iOS-faithful layout in a draggable bottom sheet.
 *
 * Layout, top to bottom:
 *   1. Toolbar           — Close (left, pill) / Presets (right, orange pill)
 *   2. Recipient row     — avatar + display name + lud16 + copy
 *                          (hidden if no `profileLookup` data for the
 *                          `recipientPubkey`)
 *   3. Hero amount       — big orange number + "sats" / fiat caption
 *   4. Preset strip      — wrapping FlowRow of pills + Custom-with-plus chip
 *   5. (Custom field)    — inline OutlinedTextField shown when isCustom
 *   6. Message field     — single-line OutlinedTextField
 *   7. Privacy dropdown  — Public / Anonymous / Private with helper text
 *   8. Instant zaps      — toggle bound to `quickZapEnabled` setting
 *   9. Zap button        — full-width orange action button. Over 1M sats
 *                          disables it; over 10K routes through a
 *                          soft-confirmation dialog.
 *
 * Wrapping `ModalBottomSheet` provides drag-handle dismiss, scrim-tap
 * dismiss, and a partial-height presentation so the sheet doesn't take
 * over the whole viewport — fixes the "impossible to dismiss" complaint
 * from the previous Dialog-based version.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ZapDialog(
    isWalletConnected: Boolean,
    onDismiss: () -> Unit,
    onZap: (amountMsats: Long, message: String, isAnonymous: Boolean, isPrivate: Boolean) -> Unit,
    onGoToWallet: () -> Unit,
    canPrivateZap: Boolean = false,
    /**
     * Lock the zap to DIP-03 private mode (private + anon toggles hidden, isPrivate held true).
     * Used when zapping a NIP-17 private reply — falling back to a public zap would attach an
     * e-tag pointing at the rumor id on public relays.
     */
    forcePrivate: Boolean = false,
    /** When opening from a quick preset (e.g. chat actions sheet), pre-select that amount in sats. */
    initialSatsHint: Int? = null,
    /** Recipient pubkey for the optional recipient header row. */
    recipientPubkey: String? = null,
    /** Profile lookup for the recipient header row. Returns null if unknown. */
    profileLookup: (String) -> ProfileData? = { null },
    /**
     * What the recipient accepts. Pass it only where Monero tips are offered
     * (posts and profiles). Null, or no Monero address, keeps the Bitcoin-only
     * sheet exactly as before.
     */
    payOptions: PayOptions? = null
) {
    val recipientProfile = recipientPubkey?.let { profileLookup(it) }
    val liveOptions = payOptions?.let {
        it.copy(lightningAddress = it.lightningAddress ?: recipientProfile?.lud16?.trim()?.ifEmpty { null })
    }
    // Decide Bitcoin-only vs Monero once per open sheet, and keep the Monero address
    // it opened with. A pay list or profile arriving while the sheet is open must not
    // swap the sheet (losing what was typed) or change the address being paid.
    val openedMoneroAddress = remember(recipientPubkey) { liveOptions?.moneroAddress }
    val options = if (openedMoneroAddress == null) null
        else (liveOptions ?: PayOptions.NONE).copy(moneroAddress = openedMoneroAddress)
    if (options == null || !options.acceptsMonero) {
        BitcoinZapSheet(
            isWalletConnected = isWalletConnected,
            onDismiss = onDismiss,
            onZap = onZap,
            onGoToWallet = onGoToWallet,
            canPrivateZap = canPrivateZap,
            forcePrivate = forcePrivate,
            initialSatsHint = initialSatsHint,
            recipientProfile = recipientProfile
        )
        return
    }
    PayChoiceSheet(
        options = options,
        isWalletConnected = isWalletConnected,
        onDismiss = onDismiss,
        onZap = onZap,
        onGoToWallet = onGoToWallet,
        canPrivateZap = canPrivateZap,
        forcePrivate = forcePrivate,
        initialSatsHint = initialSatsHint,
        recipientPubkey = recipientPubkey,
        recipientProfile = recipientProfile
    )
}

/** The Bitcoin-only zap sheet: what [ZapDialog] has always shown. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BitcoinZapSheet(
    isWalletConnected: Boolean,
    onDismiss: () -> Unit,
    onZap: (amountMsats: Long, message: String, isAnonymous: Boolean, isPrivate: Boolean) -> Unit,
    onGoToWallet: () -> Unit,
    canPrivateZap: Boolean,
    forcePrivate: Boolean,
    initialSatsHint: Int?,
    recipientProfile: ProfileData?
) {
    if (!isWalletConnected) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.zap_wallet_not_connected)) },
            text = { Text(stringResource(R.string.zap_connect_wallet)) },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    onGoToWallet()
                }) { Text(stringResource(R.string.btn_go_to_wallet)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
        return
    }

    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun closeSheet() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        // Drag handle replaces the iOS "swipe down" affordance.
    ) {
        BitcoinZapContent(
            onZap = onZap,
            closeSheet = { closeSheet() },
            canPrivateZap = canPrivateZap,
            forcePrivate = forcePrivate,
            initialSatsHint = initialSatsHint,
            recipientProfile = recipientProfile
        )
    }
}

/**
 * Body of the Bitcoin zap sheet. [header] (the Bitcoin | Monero switch) is
 * drawn under the toolbar when the recipient takes both.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BitcoinZapContent(
    onZap: (amountMsats: Long, message: String, isAnonymous: Boolean, isPrivate: Boolean) -> Unit,
    closeSheet: () -> Unit,
    canPrivateZap: Boolean,
    forcePrivate: Boolean,
    initialSatsHint: Int?,
    recipientProfile: ProfileData?,
    header: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val accent = WispThemeColors.zapColor
    val onAccent = inkOrWhite(accent)

    val fiatPrefs = remember { FiatPreferences.get(context) }
    val fiatMode by fiatPrefs.fiatMode.collectAsState()
    val fiatCurrency by fiatPrefs.currency.collectAsState()
    val interfacePrefs = remember { com.wisp.app.repo.InterfacePreferences(context) }
    val zapPrefsRepo = remember { ZapPreferences(context) }
    var presets by remember { mutableStateOf(zapPrefsRepo.getPresets().sortedBy { it.amountSats }) }
    var selectedPreset by remember { mutableStateOf<ZapPreset?>(presets.firstOrNull()) }
    var isCustom by remember { mutableStateOf(false) }
    // TextFieldValue so we can pre-select the seeded amount on focus —
    // the first keystroke then replaces the whole seed, matching the
    // iOS "first-keystroke-replaces-seed" UX.
    var customAmountTfv by remember { mutableStateOf(TextFieldValue("")) }
    val customAmount = customAmountTfv.text
    var message by remember { mutableStateOf("") }
    var isAnonymous by remember { mutableStateOf(false) }
    var isPrivate by remember(forcePrivate) { mutableStateOf(forcePrivate) }
    var instantZapsEnabled by remember { mutableStateOf(interfacePrefs.isQuickZapEnabled()) }
    var showLargeAmountConfirm by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var privacyMenuExpanded by remember { mutableStateOf(false) }
    val amountFocusRequester = remember { FocusRequester() }

    LaunchedEffect(initialSatsHint) {
        val hint = initialSatsHint ?: return@LaunchedEffect
        val h = hint.toLong().coerceAtLeast(1L)
        val match = presets.find { it.amountSats == h }
        if (match != null) {
            selectedPreset = match
            isCustom = false
            message = match.message
        } else {
            isCustom = true
            seedCustomAmount(h.toString()) { customAmountTfv = it }
            message = ""
        }
    }

    // Seed amount from the configured instant-zap amount on first open.
    // Auto-focus the field after the sheet's mount + transition has
    // settled (~450ms) — matches the iOS deferral. The seed is selected
    // so the first keystroke replaces it entirely.
    LaunchedEffect(Unit) {
        if (initialSatsHint == null) {
            val seedSats = if (fiatMode) {
                val major = interfacePrefs.getQuickZapAmountFiat()
                (major * 100.0).toLong().coerceAtLeast(0L)
            } else {
                interfacePrefs.getQuickZapAmountSats()
            }
            if (seedSats > 0) {
                isCustom = true
                seedCustomAmount(seedSats.toString()) { customAmountTfv = it }
                message = interfacePrefs.getQuickZapMessage()
            }
        }
        delay(450)
        runCatching { amountFocusRequester.requestFocus() }
    }

    val effectiveAmount: Long = if (isCustom) {
        if (fiatMode) {
            val cents = customAmount.toLongOrNull() ?: 0L
            if (cents > 0) ExchangeRateRepository.fiatToSats(cents.toDouble() / 100.0, fiatCurrency) ?: 0L else 0L
        } else {
            customAmount.toLongOrNull() ?: 0L
        }
    } else {
        selectedPreset?.amountSats ?: 0L
    }
    val effectiveMessage = if (isCustom) message else (selectedPreset?.message ?: "")
    val overHardCap = effectiveAmount > ZAP_HARD_CAP_SATS
    val canSavePreset = isCustom && effectiveAmount > 0 &&
        presets.none { it.amountSats == effectiveAmount } &&
        presets.size < 8

    // Two-row stack: scrollable content on top, pinned Zap button
    // at the bottom. `imePadding()` lifts the whole stack above the
    // keyboard so the Zap button stays visible even when the
    // amount field is focused. `navigationBarsPadding()` keeps it
    // above the gesture-nav handle on devices without IME up.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
        // ── 1. Toolbar ──────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PillButton(text = stringResource(R.string.btn_close), onClick = { closeSheet() })
            PillButton(
                text = "Presets",
                onClick = { showSavePresetDialog = true },
                contentColor = accent,
                borderColor = accent.copy(alpha = 0.45f)
            )
        }

        header?.invoke()

        // ── 2. Recipient row (optional) ─────────────────────────
        if (recipientProfile != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = recipientProfile.picture,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        recipientProfile.displayString,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    if (!recipientProfile.lud16.isNullOrBlank()) {
                        Text(
                            recipientProfile.lud16,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                if (!recipientProfile.lud16.isNullOrBlank()) {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(recipientProfile.lud16!!))
                    }) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy lightning address",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // ── 3. Hero amount ──────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val heroText = if (fiatMode && effectiveAmount > 0) {
                AmountFormatter.formatShort(effectiveAmount, context)
            } else {
                "%,d".format(effectiveAmount)
            }
            Text(
                heroText,
                color = accent,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                if (fiatMode) ExchangeRateRepository.currencyFor(fiatCurrency).code else "sats",
                color = accent.copy(alpha = 0.75f),
                style = MaterialTheme.typography.titleSmall
            )
        }

        // ── 4. Preset strip ─────────────────────────────────────
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { preset ->
                val selected = !isCustom && selectedPreset?.amountSats == preset.amountSats
                PresetPill(
                    label = AmountFormatter.formatShort(preset.amountSats, context),
                    selected = selected,
                    accent = accent,
                    onClick = {
                        selectedPreset = preset
                        isCustom = false
                        // Auto-fill the preset's optional default
                        // message only when the message field is
                        // currently empty (don't clobber typing).
                        if (preset.message.isNotEmpty() && message.isBlank()) {
                            message = preset.message
                        }
                    }
                )
            }
            CustomPlusPill(
                label = if (isCustom && effectiveAmount > 0)
                    AmountFormatter.formatShort(effectiveAmount, context)
                else "Custom",
                selected = isCustom,
                accent = accent,
                showPlus = canSavePreset,
                onClick = {
                    isCustom = true
                    if (effectiveAmount == 0L) {
                        customAmountTfv = TextFieldValue("")
                    } else {
                        // Re-seed and select-all so first keystroke replaces.
                        seedCustomAmount(customAmount) { customAmountTfv = it }
                    }
                    runCatching { amountFocusRequester.requestFocus() }
                },
                onPlusClick = {
                    // Save the current custom amount as a new preset
                    if (canSavePreset) {
                        presets = zapPrefsRepo.addPreset(
                            ZapPreset(effectiveAmount, message.trim())
                        ).sortedBy { it.amountSats }
                    }
                }
            )
        }

        // ── 5. Inline custom amount field ───────────────────────
        // Always rendered (the keyboard is up on mount per iOS),
        // but only contributes to the amount when isCustom = true.
        // The seed is pre-selected so the first keystroke replaces
        // it; preset taps switch isCustom off and the typed value
        // is preserved if the user comes back.
        OutlinedTextField(
            value = customAmountTfv,
            onValueChange = { newTfv ->
                val filtered = newTfv.text.filter { it.isDigit() }
                // Preserve cursor / selection across the digit filter.
                customAmountTfv = newTfv.copy(text = filtered)
                if (filtered.isNotEmpty()) isCustom = true
            },
            label = {
                Text(if (fiatMode) "Custom (cents)" else "Custom (sats)")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(amountFocusRequester)
        )

        // ── 6. Message ──────────────────────────────────────────
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            placeholder = { Text("Message (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // ── 7. Privacy dropdown ─────────────────────────────────
        if (!forcePrivate) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { privacyMenuExpanded = true },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val (icon, label, helper) = when {
                            isPrivate -> Triple(Icons.Filled.Lock, "Private", "Recipient only — sent via DM-relay route.")
                            isAnonymous -> Triple(Icons.Outlined.VisibilityOff, "Anonymous", "Recipient won't see your identity.")
                            else -> Triple(Icons.Outlined.Visibility, "Public", null)
                        }
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            if (helper != null) {
                                Text(
                                    helper,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = privacyMenuExpanded,
                    onDismissRequest = { privacyMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Public") },
                        leadingIcon = { Icon(Icons.Outlined.Visibility, null) },
                        onClick = {
                            isPrivate = false
                            isAnonymous = false
                            privacyMenuExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Anonymous") },
                        leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) },
                        onClick = {
                            isAnonymous = true
                            isPrivate = false
                            privacyMenuExpanded = false
                        }
                    )
                    if (canPrivateZap) {
                        DropdownMenuItem(
                            text = { Text("Private") },
                            leadingIcon = { Icon(Icons.Filled.Lock, null) },
                            onClick = {
                                isPrivate = true
                                isAnonymous = false
                                privacyMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // ── 8. Instant zaps toggle ──────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bolt),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (fiatMode) "Instant payments" else "Instant zaps",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = instantZapsEnabled,
                    onCheckedChange = {
                        instantZapsEnabled = it
                        interfacePrefs.setQuickZapEnabled(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = accent,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }

        } // end scrollable content Column

        // ── 9. Zap button — pinned to the bottom of the sheet ──
        // Lives outside the scrollable region above so it stays on
        // screen even when the keyboard is up. The outer Column's
        // imePadding() ensures it floats above the IME.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (overHardCap) {
                Text(
                    "Max ${"%,d".format(ZAP_HARD_CAP_SATS)} sats per zap",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            Button(
                onClick = {
                    if (effectiveAmount > ZAP_SOFT_CONFIRM_SATS) {
                        showLargeAmountConfirm = true
                    } else {
                        onZap(effectiveAmount * 1000, effectiveMessage.ifEmpty { message }, isAnonymous, isPrivate)
                    }
                },
                enabled = effectiveAmount > 0 && !overHardCap,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = onAccent,
                    disabledContainerColor = accent.copy(alpha = 0.35f)
                )
            ) {
                Icon(
                    painter = painterResource(
                        if (fiatMode) R.drawable.ic_coin_stack else R.drawable.ic_bolt
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (fiatMode) "Zap ${AmountFormatter.formatShort(effectiveAmount, context)}"
                    else "Zap ${"%,d".format(effectiveAmount)} sats",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        }
    }

    // 10K-sat soft-confirmation dialog. Large zaps surface a "double-check
    // before sending" prompt so a stray preset tap doesn't drain a wallet.
    if (showLargeAmountConfirm) {
        AlertDialog(
            onDismissRequest = { showLargeAmountConfirm = false },
            title = { Text("Zap %,d sats?".format(effectiveAmount)) },
            text = { Text("This is a large amount, double-check before sending.") },
            confirmButton = {
                Button(
                    onClick = {
                        showLargeAmountConfirm = false
                        onZap(effectiveAmount * 1000, effectiveMessage.ifEmpty { message }, isAnonymous, isPrivate)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) { Text("Send", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showLargeAmountConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (showSavePresetDialog) {
        SaveZapPresetDialog(
            currentAmount = if (effectiveAmount > 0) effectiveAmount.toString() else "",
            onSave = { preset ->
                presets = zapPrefsRepo.addPreset(preset).sortedBy { it.amountSats }
                selectedPreset = preset
                isCustom = false
                showSavePresetDialog = false
            },
            onDismiss = { showSavePresetDialog = false }
        )
    }
}

// ─── Bitcoin | Monero ────────────────────────────────────────────────────

/**
 * Send sheet for a recipient who takes Monero (and maybe Bitcoin too).
 *
 * Neither coin is primary: the switch lists [PayOptions.methods] and opens on
 * the viewer's last-used one. The last-used choice is only written when the
 * recipient offered a choice. Monero is a hand-off: Pinket opens the phone's
 * wallet with a payment link and never learns whether it was paid, so there is
 * no success state and nothing is published or recorded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PayChoiceSheet(
    options: PayOptions,
    isWalletConnected: Boolean,
    onDismiss: () -> Unit,
    onZap: (amountMsats: Long, message: String, isAnonymous: Boolean, isPrivate: Boolean) -> Unit,
    onGoToWallet: () -> Unit,
    canPrivateZap: Boolean,
    forcePrivate: Boolean,
    initialSatsHint: Int?,
    recipientPubkey: String?,
    recipientProfile: ProfileData?
) {
    val moneroAddress = options.moneroAddress ?: return
    val context = LocalContext.current
    val interfacePrefs = remember { com.wisp.app.repo.InterfacePreferences(context) }
    var chosenMethod by remember {
        mutableStateOf(options.initialMethod(interfacePrefs.getLastPayMethod()) ?: PayMethod.MONERO)
    }
    // Lightning address can come and go while open; never sit on a side that is gone.
    val method = if (chosenMethod in options.methods) chosenMethod else PayMethod.MONERO
    var noWalletAmount by remember { mutableStateOf<BigDecimal?>(null) }
    val recipientName = recipientProfile?.displayString
        ?: recipientPubkey?.toNpub()?.let { "${it.take(12)}...${it.takeLast(4)}" }
        ?: Monero.shortAddress(moneroAddress)

    fun rememberChoice(used: PayMethod) {
        if (options.hasChoice) interfacePrefs.setLastPayMethod(used)
    }

    // No app on this phone opens monero: links. Swap the sheet for the QR / copy fallback.
    noWalletAmount?.let { amount ->
        MoneroNoWalletDialog(
            address = moneroAddress,
            amount = amount,
            recipientName = recipientName,
            onDismiss = onDismiss
        )
        return
    }

    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun closeSheet() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    val header: (@Composable () -> Unit)? = if (options.hasChoice) {
        { PayMethodSwitch(methods = options.methods, selected = method, onSelect = { chosenMethod = it }) }
    } else null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        when (method) {
            PayMethod.BITCOIN -> if (isWalletConnected) {
                BitcoinZapContent(
                    onZap = { amountMsats, message, isAnonymous, isPrivate ->
                        rememberChoice(PayMethod.BITCOIN)
                        onZap(amountMsats, message, isAnonymous, isPrivate)
                    },
                    closeSheet = { closeSheet() },
                    canPrivateZap = canPrivateZap,
                    forcePrivate = forcePrivate,
                    initialSatsHint = initialSatsHint,
                    recipientProfile = recipientProfile,
                    header = header
                )
            } else {
                WalletNotConnectedContent(
                    closeSheet = { closeSheet() },
                    onGoToWallet = {
                        onDismiss()
                        onGoToWallet()
                    },
                    header = header
                )
            }
            PayMethod.MONERO -> MoneroContent(
                address = moneroAddress,
                recipientName = recipientName,
                recipientPicture = recipientProfile?.picture,
                closeSheet = { closeSheet() },
                onOpenWallet = { amount ->
                    rememberChoice(PayMethod.MONERO)
                    interfacePrefs.setLastMoneroAmount(amount)
                    if (MoneroWallet.open(context, moneroAddress, amount)) closeSheet()
                    else noWalletAmount = amount
                },
                header = header
            )
        }
    }
}

/** Two-way "Bitcoin | Monero" switch. Neutral colors; the selected side is raised. */
@Composable
private fun PayMethodSwitch(
    methods: List<PayMethod>,
    selected: PayMethod,
    onSelect: (PayMethod) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .padding(3.dp)
                .selectableGroup()
        ) {
            methods.forEach { m ->
                val isSelected = m == selected
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(m) }),
                    shape = CircleShape,
                    color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)) else null
                ) {
                    Text(
                        stringResource(
                            when (m) {
                                PayMethod.BITCOIN -> R.string.pay_method_bitcoin
                                PayMethod.MONERO -> R.string.pay_method_monero
                            }
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** Bitcoin side of the switch when no wallet is connected: the usual message, inline. */
@Composable
private fun WalletNotConnectedContent(
    closeSheet: () -> Unit,
    onGoToWallet: () -> Unit,
    header: (@Composable () -> Unit)?
) {
    val accent = WispThemeColors.zapColor
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            PillButton(text = stringResource(R.string.btn_close), onClick = closeSheet)
        }
        header?.invoke()
        Text(
            stringResource(R.string.zap_wallet_not_connected),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            stringResource(R.string.zap_connect_wallet),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onGoToWallet,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = inkOrWhite(accent))
        ) {
            Text(stringResource(R.string.btn_go_to_wallet), fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}

/**
 * Monero side: recipient, short address with copy, XMR amount pills with a
 * custom "+" entry, an optional fiat hint, and "Open wallet".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoneroContent(
    address: String,
    recipientName: String,
    recipientPicture: String?,
    closeSheet: () -> Unit,
    onOpenWallet: (BigDecimal) -> Unit,
    header: (@Composable () -> Unit)?
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val accent = WispThemeColors.zapColor
    val interfacePrefs = remember { com.wisp.app.repo.InterfacePreferences(context) }
    val fiatPrefs = remember { FiatPreferences.get(context) }
    val fiatCurrency by fiatPrefs.currency.collectAsState()
    val presets = Monero.PRESET_AMOUNTS

    // Open on the last amount used: its preset pill, or the custom field.
    val lastAmount = remember { interfacePrefs.getLastMoneroAmount() }
    val lastPreset = remember { lastAmount?.let { last -> presets.firstOrNull { it.compareTo(last) == 0 } } }
    var selectedPreset by remember { mutableStateOf(if (lastAmount == null) presets.first() else lastPreset) }
    var isCustom by remember { mutableStateOf(lastAmount != null && lastPreset == null) }
    var customText by remember {
        mutableStateOf(if (lastAmount != null && lastPreset == null) Monero.formatAmount(lastAmount) else "")
    }
    var focusTick by remember { mutableStateOf(0) }
    val customFocus = remember { FocusRequester() }
    LaunchedEffect(focusTick) {
        if (focusTick > 0) runCatching { customFocus.requestFocus() }
    }

    val amount: BigDecimal? = if (isCustom) Monero.parseAmount(customText) else selectedPreset

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                PillButton(text = stringResource(R.string.btn_close), onClick = closeSheet)
            }

            header?.invoke()

            // Recipient: avatar, name, short address + copy.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = recipientPicture,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        recipientName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        Monero.shortAddress(address),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(address)) }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.monero_copy_address),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Amount pills in XMR, then the custom "+" entry, all on one row.
            // The amount pills share the width; the custom amount itself shows
            // in the field below, so the "+" pill stays one small size.
            val pillFontSize = remember { mutableStateOf(TextUnit.Unspecified) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                presets.forEach { preset ->
                    PresetPill(
                        label = stringResource(R.string.monero_amount_xmr, Monero.formatAmount(preset)),
                        selected = !isCustom && selectedPreset?.compareTo(preset) == 0,
                        accent = accent,
                        onClick = {
                            selectedPreset = preset
                            isCustom = false
                        },
                        modifier = Modifier.weight(1f),
                        horizontalPadding = 4.dp,
                        fitFontSize = pillFontSize
                    )
                }
                Surface(
                    modifier = Modifier.clickable {
                        isCustom = true
                        focusTick++
                    },
                    shape = CircleShape,
                    color = if (isCustom) accent else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.monero_custom_amount),
                        tint = if (isCustom) inkOrWhite(accent) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .size(20.dp)
                    )
                }
            }

            if (isCustom) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { new ->
                        val filtered = new.filter { it.isDigit() || it == '.' || it == ',' }
                        if (filtered.count { it == '.' || it == ',' } <= 1 && filtered.length <= 20) {
                            customText = filtered
                        }
                    },
                    label = { Text(stringResource(R.string.monero_amount_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(customFocus)
                )
            }

            // Fiat hint only when a price is known. Sending never waits on it.
            val fiat = amount?.let { ExchangeRateRepository.xmrToFiat(it.toDouble(), fiatCurrency) }
            if (amount != null && fiat != null) {
                Text(
                    stringResource(
                        R.string.monero_amount_fiat_hint,
                        Monero.formatAmount(amount),
                        AmountFormatter.formatFiatApprox(fiat, fiatCurrency)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 16.dp)
        ) {
            Button(
                onClick = { amount?.let(onOpenWallet) },
                enabled = amount != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = inkOrWhite(accent),
                    disabledContainerColor = accent.copy(alpha = 0.35f)
                )
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_monero),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.monero_open_wallet),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        }
    }
}

/**
 * Shown when no app on this phone opens monero: links. The payment link as a
 * QR code for a wallet on another device, plus the address and amount to paste.
 */
@Composable
private fun MoneroNoWalletDialog(
    address: String,
    amount: BigDecimal,
    recipientName: String,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val amountText = Monero.formatAmount(amount)
    val uri = remember(address, amount) { Monero.paymentUri(address, amount) }
    val qrBitmap = remember(uri) { generateQrBitmap(uri) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.monero_send_title, amountText, recipientName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, stringResource(R.string.btn_close))
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Same QR treatment as the lightning address dialog.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(256.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(8.dp)
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize()
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .padding(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_monero),
                            contentDescription = null,
                            // Neutral, like the code itself: the badge sits on white in every theme.
                            tint = Color.Black,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                Text(
                    address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        text = stringResource(R.string.monero_copy_address),
                        onClick = { clipboard.setText(AnnotatedString(address)) }
                    )
                    PillButton(
                        text = stringResource(R.string.monero_copy_amount),
                        onClick = { clipboard.setText(AnnotatedString(amountText)) }
                    )
                }
                Spacer(Modifier.height(12.dp))

                Text(
                    stringResource(R.string.monero_no_wallet_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────

private const val ZAP_SOFT_CONFIRM_SATS = 10_000L
private const val ZAP_HARD_CAP_SATS = 1_000_000L

/**
 * Seed the custom-amount field with the given text AND select the
 * whole range — so the next keystroke replaces the seed entirely.
 * Lets the user open the sheet, see the configured instant-zap
 * amount, then type a new value over it without backspacing first.
 */
private fun seedCustomAmount(text: String, set: (TextFieldValue) -> Unit) {
    set(TextFieldValue(text = text, selection = TextRange(0, text.length)))
}

/**
 * Pill-shaped text button — used for the toolbar's Close + Presets
 * actions. Border-only by default, fillable via `borderColor`.
 */
@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
        )
    }
}

/** Single preset chip in the FlowRow. Selected = filled accent + white. */
@Composable
private fun PresetPill(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 18.dp,
    /**
     * Label size shared by a row of pills. When given, the labels shrink
     * together until each one fits (narrow screens, large text) instead of
     * being cut off.
     */
    fitFontSize: MutableState<TextUnit>? = null
) {
    val onAccent = inkOrWhite(accent)
    val baseStyle = MaterialTheme.typography.labelLarge
    val textStyle = fitFontSize?.value?.takeIf { it.isSpecified }
        ?.let { baseStyle.copy(fontSize = it) } ?: baseStyle
    var fitted by remember(label) { mutableStateOf(fitFontSize == null) }
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) accent else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            label,
            style = textStyle,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) onAccent else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            onTextLayout = { result ->
                if (fitFontSize == null) return@Text
                val size = textStyle.fontSize
                if (result.didOverflowWidth && size.value > 9f) {
                    val next = size * 0.92f
                    val shared = fitFontSize.value
                    if (shared.isUnspecified || next.value < shared.value) fitFontSize.value = next
                } else {
                    fitted = true
                }
            },
            modifier = Modifier
                .padding(horizontal = horizontalPadding, vertical = 8.dp)
                .drawWithContent { if (fitted) drawContent() }
        )
    }
}

/** Custom-amount chip. When selected AND not yet in the preset list,
 *  the trailing + badge becomes tappable to save the value as a preset. */
@Composable
private fun CustomPlusPill(
    label: String,
    selected: Boolean,
    accent: Color,
    showPlus: Boolean,
    onClick: () -> Unit,
    onPlusClick: () -> Unit
) {
    val onAccent = inkOrWhite(accent)
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) accent else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 18.dp, end = if (showPlus) 4.dp else 18.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) onAccent else MaterialTheme.colorScheme.onSurface
            )
            if (showPlus) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(onAccent.copy(alpha = 0.18f))
                        .clickable(onClick = onPlusClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Save as preset",
                        tint = onAccent,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * Translate raw Lightning/SDK error strings into plain user-facing
 * copy. Mirrors iOS `ZapAnimationStore.friendlyMessage(for:)`.
 * Fallback: extract the substring between the first `("` and `")`
 * (Swift enum description wrapper) if present; otherwise pass through
 * the raw error.
 */
internal fun friendlyZapErrorMessage(raw: String?): String {
    val msg = raw?.trim().orEmpty()
    if (msg.isEmpty()) return "Zap failed."
    val lower = msg.lowercase()
    return when {
        "insufficient funds" in lower || "insufficient balance" in lower ->
            "Not enough sats in your wallet."
        "no route" in lower || "route not found" in lower || "unreachable" in lower ->
            "Couldn't find a payment route to the recipient. Try again later."
        "expired" in lower || "invoice has expired" in lower ->
            "The lightning invoice expired before it could be paid. Try again."
        "timeout" in lower || "timed out" in lower ->
            "The payment timed out. Check your connection and try again."
        "no lud16" in lower || "no lightning address" in lower ->
            "This account doesn't have a lightning address."
        "lnurl" in lower && "400" in lower ->
            "The recipient's lightning provider rejected this zap. Try a different amount."
        "amount too small" in lower || "below minimum" in lower ->
            "Amount is below the recipient's minimum. Try a larger zap."
        "amount too large" in lower || "above maximum" in lower ->
            "Amount is above the recipient's maximum. Try a smaller zap."
        else -> {
            val start = msg.indexOf("(\"")
            val end = msg.indexOf("\")", startIndex = (start + 2).coerceAtLeast(0))
            if (start >= 0 && end > start + 2) msg.substring(start + 2, end) else msg
        }
    }
}

/**
 * Simple "save as preset" dialog used by the Presets pill in the
 * toolbar. The composer's inline + badge on the Custom chip handles
 * the single-tap save flow; this dialog is for adding/editing presets
 * with an explicit message.
 */
@Composable
private fun SaveZapPresetDialog(
    currentAmount: String,
    onSave: (ZapPreset) -> Unit,
    onDismiss: () -> Unit
) {
    var amount by remember { mutableStateOf(currentAmount) }
    var presetMessage by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save preset") },
        text = {
            Column {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() } },
                    label = { Text("Amount (sats)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = presetMessage,
                    onValueChange = { presetMessage = it.replace(",", "").replace(":", "") },
                    label = { Text("Message (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sats = amount.toLongOrNull() ?: return@Button
                    onSave(ZapPreset(sats, presetMessage.trim()))
                },
                enabled = (amount.toLongOrNull() ?: 0L) > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WispThemeColors.zapColor,
                    contentColor = inkOrWhite(WispThemeColors.zapColor)
                )
            ) {
                Text(stringResource(R.string.btn_save), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}
