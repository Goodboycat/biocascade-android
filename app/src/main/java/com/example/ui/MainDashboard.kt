package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.auth.signOutUser
import com.example.physiology.PhysiologyEngine
import com.example.ui.theme.BioSkinPalette
import com.example.ui.theme.DashboardSkin
import com.example.ui.theme.LocalBioSkin
import com.example.ui.theme.TelemetryNumericStyle
import com.example.viewmodel.PhysiologyViewModel

enum class DashboardTab {
    BODY,
    SEARCH,
    RECORDS,
}

@Composable
fun MainDashboard(
    viewModel: PhysiologyViewModel,
    onSignedOut: () -> Unit,
) {
    val skin = LocalBioSkin.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val physiologyState by viewModel.physiologyState.collectAsStateWithLifecycle()
    val selectedRegion by viewModel.selectedRegion.collectAsStateWithLifecycle()
    val activeLever by viewModel.activeLever.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val medicalSearchState by viewModel.medicalSearchState.collectAsStateWithLifecycle()
    val profilesState by viewModel.profilesState.collectAsStateWithLifecycle()
    val recordsState by viewModel.recordsState.collectAsStateWithLifecycle()
    val auditLogsState by viewModel.auditLogsState.collectAsStateWithLifecycle()
    val hipaaLockEnabled by viewModel.hipaaLockEnabled.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(DashboardTab.BODY) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        val msg = statusMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    if (activeTab != DashboardTab.BODY) {
        BackHandler { activeTab = DashboardTab.BODY }
    }

    Scaffold(
        containerColor = skin.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CompactHeaderBar(
                activeTab = activeTab,
                simElapsedSeconds = physiologyState.simElapsedSeconds,
                speedMultiplier = physiologyState.timeSpeedMultiplier,
                currentSkin = skin,
                onSelectTab = { activeTab = it },
                onSelectSkin = viewModel::setSkin,
                onSignOut = {
                    signOutUser(credentialManager, scope, onSignOutComplete = onSignedOut)
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
        ) {
            when (activeTab) {
                DashboardTab.BODY -> {
                    BodyLabScreen(
                        state = physiologyState,
                        selectedRegion = selectedRegion,
                        activeLever = activeLever,
                        viewModel = viewModel,
                        isSaving = isSaving,
                    )
                }

                DashboardTab.SEARCH -> {
                    MedSearchScreen(
                        query = searchQuery,
                        searchState = medicalSearchState,
                        onQueryChange = viewModel::updateSearchQuery,
                        onSearch = viewModel::runMedicalSearch,
                        onJumpToParam = { param ->
                            viewModel.selectLever(param)
                            activeTab = DashboardTab.BODY
                        },
                    )
                }

                DashboardTab.RECORDS -> {
                    RecordsScreen(
                        state = physiologyState,
                        profilesState = profilesState,
                        recordsState = recordsState,
                        auditLogsState = auditLogsState,
                        hipaaLockEnabled = hipaaLockEnabled,
                        isSaving = isSaving,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactHeaderBar(
    activeTab: DashboardTab,
    simElapsedSeconds: Double,
    speedMultiplier: Float,
    currentSkin: BioSkinPalette,
    onSelectTab: (DashboardTab) -> Unit,
    onSelectSkin: (DashboardSkin) -> Unit,
    onSignOut: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        color = currentSkin.surface,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Compact Tab Pills directly in header so Body viewport stays large
                Surface(
                    color = currentSkin.surfaceElevated,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.border(1.dp, currentSkin.borderSubtle, RoundedCornerShape(10.dp)),
                ) {
                    Row(modifier = Modifier.padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        HeaderTabPill(
                            label = stringResource(R.string.tab_body_lab),
                            selected = activeTab == DashboardTab.BODY,
                            skin = currentSkin,
                            tag = "header_tab_body",
                            onClick = { onSelectTab(DashboardTab.BODY) },
                        )
                        HeaderTabPill(
                            label = stringResource(R.string.tab_med_search),
                            selected = activeTab == DashboardTab.SEARCH,
                            skin = currentSkin,
                            tag = "header_tab_search",
                            onClick = { onSelectTab(DashboardTab.SEARCH) },
                        )
                        HeaderTabPill(
                            label = stringResource(R.string.tab_records),
                            selected = activeTab == DashboardTab.RECORDS,
                            skin = currentSkin,
                            tag = "header_tab_records",
                            onClick = { onSelectTab(DashboardTab.RECORDS) },
                        )
                    }
                }

                // Live Simulation Clock + Skin Switcher / Sign Out
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "T+${PhysiologyEngine.formatDuration(simElapsedSeconds)} (${speedMultiplier.toInt()}x)",
                        style = TelemetryNumericStyle.copy(
                            fontSize = MaterialTheme.typography.labelSmall.fontSize,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = currentSkin.primaryAccent,
                    )

                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(36.dp).testTag("skin_menu_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Palette,
                                contentDescription = "Switch dark dashboard skin or sign out",
                                tint = currentSkin.textPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(currentSkin.surfaceElevated),
                        ) {
                            DashboardSkin.entries.forEach { skinOption ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (skinOption == currentSkin.skin) currentSkin.primaryAccent
                                                        else currentSkin.borderSubtle
                                                    ),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text(skinOption.displayName, color = currentSkin.textPrimary, style = MaterialTheme.typography.titleSmall)
                                                Text(skinOption.subtitle, color = currentSkin.textSecondary, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    },
                                    onClick = {
                                        onSelectSkin(skinOption)
                                        showMenu = false
                                    },
                                )
                            }
                            HorizontalDivider(color = currentSkin.borderSubtle)
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = currentSkin.criticalColor)
                                },
                                text = { Text("Sign Out", color = currentSkin.criticalColor) },
                                onClick = {
                                    showMenu = false
                                    onSignOut()
                                },
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = currentSkin.borderSubtle, thickness = 1.dp)
        }
    }
}

@Composable
private fun HeaderTabPill(
    label: String,
    selected: Boolean,
    skin: BioSkinPalette,
    tag: String,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) skin.primaryAccent else skin.surfaceElevated,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag(tag),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = if (selected) skin.onAccent else skin.textSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
