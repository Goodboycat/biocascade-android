package com.example.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.physiology.BiologicalSex
import com.example.physiology.HipaaAuditEntry
import com.example.physiology.HumanBaselineProfile
import com.example.physiology.PhysiologyEngine
import com.example.physiology.PhysiologyState
import com.example.physiology.SimulationRecord
import com.example.ui.theme.BioSkinPalette
import com.example.ui.theme.LocalBioSkin
import com.example.ui.theme.TelemetryNumericStyle
import com.example.viewmodel.PhysiologyViewModel
import com.example.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordsScreen(
    state: PhysiologyState,
    profilesState: UiState<List<HumanBaselineProfile>>,
    recordsState: UiState<List<SimulationRecord>>,
    auditLogsState: UiState<List<HipaaAuditEntry>>,
    hipaaLockEnabled: Boolean,
    isSaving: Boolean,
    viewModel: PhysiologyViewModel,
    modifier: Modifier = Modifier,
) {
    val skin = LocalBioSkin.current

    var label by remember(state.profile.id) { mutableStateOf(state.profile.label) }
    var ageText by remember(state.profile.id) { mutableStateOf(state.profile.ageYears.toString()) }
    var sex by remember(state.profile.id) { mutableStateOf(BiologicalSex.fromRaw(state.profile.biologicalSex)) }
    var weightText by remember(state.profile.id) { mutableStateOf(state.profile.weightKg.toString()) }
    var heightText by remember(state.profile.id) { mutableStateOf(state.profile.heightCm.toString()) }
    var crText by remember(state.profile.id) { mutableStateOf(state.profile.serumCreatinineMgDl.toString()) }
    var hrText by remember(state.profile.id) { mutableStateOf(state.profile.restingHeartRateBpm.toString()) }
    var sbpText by remember(state.profile.id) { mutableStateOf(state.profile.restingSystolicMmHg.toString()) }
    var dbpText by remember(state.profile.id) { mutableStateOf(state.profile.restingDiastolicMmHg.toString()) }

    val draftProfile = HumanBaselineProfile(
        id = state.profile.id,
        userId = state.profile.userId,
        label = label.trim().ifEmpty { "Adult Baseline" }.take(80),
        ageYears = (ageText.toLongOrNull() ?: 32L).coerceIn(1L, 120L),
        biologicalSex = sex.name,
        weightKg = (weightText.toDoubleOrNull() ?: 72.0).coerceIn(20.0, 300.0),
        heightCm = (heightText.toDoubleOrNull() ?: 175.0).coerceIn(80.0, 250.0),
        serumCreatinineMgDl = (crText.toDoubleOrNull() ?: 0.95).coerceIn(0.2, 15.0),
        restingHeartRateBpm = (hrText.toLongOrNull() ?: 68L).coerceIn(30L, 180L),
        restingSystolicMmHg = (sbpText.toLongOrNull() ?: 118L).coerceIn(60L, 240L),
        restingDiastolicMmHg = (dbpText.toLongOrNull() ?: 76L).coerceIn(40L, 140L),
    )
    val derived = draftProfile.derivedStats()

    var showPdfPassphraseDialog by remember { mutableStateOf(false) }
    var pdfPassphrase by remember { mutableStateOf("") }

    val createPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri != null && pdfPassphrase.length >= 4) {
            viewModel.exportEncryptedPdf(uri, pdfPassphrase)
            pdfPassphrase = ""
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(skin.background)
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Linked Human Biometrics & Derived Constants
        item {
            Surface(
                color = skin.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, skin.borderSubtle, RoundedCornerShape(16.dp)),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "LINKED HUMAN BIOMETRICS (EQUATION INPUTS)",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = skin.primaryAccent,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BioInputField("Label", label, { label = it }, skin, KeyboardType.Text, Modifier.weight(1.4f))
                        BioInputField("Age (yr)", ageText, { ageText = it }, skin, KeyboardType.Number, Modifier.weight(0.7f))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BiologicalSex.entries.forEach { option ->
                            val selected = sex == option
                            Surface(
                                color = if (selected) skin.primaryAccent else skin.surfaceElevated,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .border(1.dp, skin.borderSubtle, RoundedCornerShape(10.dp))
                                    .clickable { sex = option },
                            ) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) skin.onAccent else skin.textPrimary,
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BioInputField("Weight (kg)", weightText, { weightText = it }, skin, KeyboardType.Decimal, Modifier.weight(1f))
                        BioInputField("Height (cm)", heightText, { heightText = it }, skin, KeyboardType.Decimal, Modifier.weight(1f))
                        BioInputField("Cr (mg/dL)", crText, { crText = it }, skin, KeyboardType.Decimal, Modifier.weight(1f))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BioInputField("Rest HR", hrText, { hrText = it }, skin, KeyboardType.Number, Modifier.weight(1f))
                        BioInputField("Rest SBP", sbpText, { sbpText = it }, skin, KeyboardType.Number, Modifier.weight(1f))
                        BioInputField("Rest DBP", dbpText, { dbpText = it }, skin, KeyboardType.Number, Modifier.weight(1f))
                    }

                    // Live Linked Mathematical Constants
                    Surface(
                        color = skin.surfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "DERIVED LINKED CONSTANTS",
                                style = MaterialTheme.typography.labelSmall,
                                color = skin.textSecondary,
                            )
                            Text(
                                text = "BMI ${String.format("%.1f", derived.bmi)} • BSA ${String.format("%.2f", derived.bsaM2)} m² • Blood Vol ${derived.totalBloodVolumeMl.toInt()} mL",
                                style = TelemetryNumericStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                                color = skin.primaryAccent,
                            )
                            Text(
                                text = "TBW ${String.format("%.1f", derived.totalBodyWaterL)} L • CKD-EPI eGFR ${String.format("%.1f", derived.baselineEgfr)} mL/min • CO ${String.format("%.1f", derived.restingCardiacOutputLMin)} L/m",
                                style = TelemetryNumericStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                                color = skin.secondaryAccent,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.applyBaselineProfile(draftProfile) },
                            modifier = Modifier.weight(1f).testTag("apply_profile_button"),
                        ) {
                            Text("Apply Live", color = skin.textPrimary)
                        }
                        Button(
                            onClick = { viewModel.saveBaselineProfile(draftProfile) },
                            enabled = !isSaving,
                            colors = ButtonDefaults.buttonColors(containerColor = skin.primaryAccent, contentColor = skin.onAccent),
                            modifier = Modifier.weight(1f).testTag("save_profile_button"),
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Encrypt & Sync")
                        }
                    }
                }
            }
        }

        // 2. HIPAA Safeguards & Encrypted PDF Export
        item {
            Surface(
                color = skin.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, skin.borderSubtle, RoundedCornerShape(16.dp)),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = skin.secondaryAccent)
                        Text(
                            text = "HIPAA SAFEGUARDS & ENCRYPTED PDF EXPORT",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = skin.secondaryAccent,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Session Privacy Lock", style = MaterialTheme.typography.titleSmall, color = skin.textPrimary)
                            Text("AES-256-GCM local vault active: ${if (viewModel.hasLocalEncryptedVault()) "Protected" else "Empty"}", style = MaterialTheme.typography.bodySmall, color = skin.textSecondary)
                        }
                        Switch(checked = hipaaLockEnabled, onCheckedChange = viewModel::setHipaaLock)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showPdfPassphraseDialog = true },
                            enabled = !isSaving,
                            colors = ButtonDefaults.buttonColors(containerColor = skin.secondaryAccent, contentColor = skin.onAccent),
                            modifier = Modifier.weight(1f).testTag("export_pdf_button"),
                        ) {
                            Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Export Encrypted PDF")
                        }
                        OutlinedButton(
                            onClick = { viewModel.clearLocalVault() },
                            modifier = Modifier.testTag("clear_vault_button"),
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = skin.textSecondary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Wipe Cache", color = skin.textSecondary)
                        }
                    }
                }
            }
        }

        // 3. Saved Profiles
        item {
            Text(
                text = "CLOUD-SYNCED BASELINE PROFILES",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = skin.textSecondary,
            )
        }
        when (profilesState) {
            UiState.Loading -> item { CircularProgressIndicator(color = skin.primaryAccent, modifier = Modifier.size(24.dp)) }
            is UiState.Error -> item { Text(profilesState.message, color = skin.criticalColor, style = MaterialTheme.typography.bodySmall) }
            is UiState.Success -> {
                if (profilesState.data.isEmpty()) {
                    item {
                        Text("No saved profiles yet. Customize biometrics above and tap Encrypt & Sync.", style = MaterialTheme.typography.bodySmall, color = skin.textSecondary)
                    }
                } else {
                    items(profilesState.data, key = { it.id }) { prof ->
                        Surface(
                            color = skin.surfaceElevated,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, skin.borderSubtle, RoundedCornerShape(12.dp))
                                .clickable { viewModel.applyBaselineProfile(prof) },
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(prof.label, style = MaterialTheme.typography.titleSmall, color = skin.textPrimary)
                                    Text(
                                        text = "${prof.ageYears}y ${prof.biologicalSex} • ${prof.weightKg}kg • eGFR ${String.format("%.0f", prof.derivedStats().baselineEgfr)}",
                                        style = TelemetryNumericStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                        color = skin.primaryAccent,
                                    )
                                }
                                Text("Load", style = MaterialTheme.typography.labelMedium, color = skin.secondaryAccent)
                            }
                        }
                    }
                }
            }
        }

        // 4. Saved Simulation Snapshots
        item {
            Text(
                text = "ENCRYPTED SIMULATION RECORDS",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = skin.textSecondary,
            )
        }
        when (recordsState) {
            UiState.Loading -> item { CircularProgressIndicator(color = skin.primaryAccent, modifier = Modifier.size(24.dp)) }
            is UiState.Error -> item { Text(recordsState.message, color = skin.criticalColor, style = MaterialTheme.typography.bodySmall) }
            is UiState.Success -> {
                if (recordsState.data.isEmpty()) {
                    item {
                        Text("Tap the Save icon in Body Lab to archive a physiological cascade snapshot.", style = MaterialTheme.typography.bodySmall, color = skin.textSecondary)
                    }
                } else {
                    items(recordsState.data, key = { it.id }) { rec ->
                        Surface(
                            color = skin.surfaceElevated,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, skin.borderSubtle, RoundedCornerShape(12.dp)),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(rec.stimulusSummary, style = MaterialTheme.typography.titleSmall, color = skin.textPrimary)
                                    Text(
                                        text = "T+${PhysiologyEngine.formatDuration(rec.simElapsedSeconds)} • G ${rec.glucoseMgDl.toInt()} • K⁺ ${String.format("%.1f", rec.potassiumMeqL)} • HR ${rec.heartRateBpm} • BP ${rec.systolicBp}/${rec.diastolicBp}",
                                        style = TelemetryNumericStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                        color = skin.primaryAccent,
                                    )
                                    Text(
                                        text = "SHA-256: ${rec.encryptedPayloadHash.take(16)}…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = skin.textSecondary,
                                    )
                                }
                                IconButton(onClick = { viewModel.deleteRecord(rec.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete record", tint = skin.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Immutable HIPAA Audit Trail
        item {
            Text(
                text = "IMMUTABLE AUDIT LOG",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = skin.textSecondary,
            )
        }
        when (auditLogsState) {
            UiState.Loading -> {}
            is UiState.Error -> item { Text(auditLogsState.message, color = skin.criticalColor, style = MaterialTheme.typography.bodySmall) }
            is UiState.Success -> {
                items(auditLogsState.data.take(6), key = { it.id }) { log ->
                    val ts = log.createdAt?.toDate()?.let {
                        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(it)
                    } ?: "Pending"
                    Text(
                        text = "[$ts] ${log.action}: ${log.detail}",
                        style = TelemetryNumericStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                        color = skin.textSecondary,
                    )
                }
            }
        }
    }

    if (showPdfPassphraseDialog) {
        AlertDialog(
            onDismissRequest = { showPdfPassphraseDialog = false },
            containerColor = skin.surface,
            title = { Text(stringResource(R.string.export_pdf_title), color = skin.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.export_pdf_subtitle), style = MaterialTheme.typography.bodySmall, color = skin.textSecondary)
                    OutlinedTextField(
                        value = pdfPassphrase,
                        onValueChange = { pdfPassphrase = it },
                        label = { Text("Encryption Passphrase (min 4 chars)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = skin.primaryAccent,
                            unfocusedBorderColor = skin.borderSubtle,
                            focusedTextColor = skin.textPrimary,
                            unfocusedTextColor = skin.textPrimary,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("pdf_passphrase_input"),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPdfPassphraseDialog = false
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                        createPdfLauncher.launch("BioCascade_Clinical_Report_$stamp.pdf")
                    },
                    enabled = pdfPassphrase.length >= 4,
                    colors = ButtonDefaults.buttonColors(containerColor = skin.primaryAccent, contentColor = skin.onAccent),
                    modifier = Modifier.testTag("confirm_pdf_export_button"),
                ) {
                    Text("Save Encrypted PDF")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPdfPassphraseDialog = false }) {
                    Text("Cancel", color = skin.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun BioInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    skin: BioSkinPalette,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = TelemetryNumericStyle.copy(
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            color = skin.textPrimary,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = skin.primaryAccent,
            unfocusedBorderColor = skin.borderSubtle,
            focusedLabelColor = skin.primaryAccent,
            unfocusedLabelColor = skin.textSecondary,
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    )
}
