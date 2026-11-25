package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.physiology.BiochemicalIngredient
import com.example.physiology.BodyRegion
import com.example.physiology.CausalNode
import com.example.physiology.IngredientCatalog
import com.example.physiology.IngredientCategory
import com.example.physiology.ManipulableParam
import com.example.physiology.PhysiologyEngine
import com.example.physiology.PhysiologyState
import com.example.physiology.SeverityLevel
import com.example.ui.theme.BioSkinPalette
import com.example.ui.theme.LocalBioSkin
import com.example.ui.theme.TelemetryNumericStyle
import com.example.viewmodel.PhysiologyViewModel

@Composable
fun BodyLabScreen(
    state: PhysiologyState,
    selectedRegion: BodyRegion?,
    activeIngredient: BiochemicalIngredient,
    viewModel: PhysiologyViewModel,
    isSaving: Boolean,
    modifier: Modifier = Modifier,
) {
    val skin = LocalBioSkin.current
    // 0 = Compact (Body Maximized), 1 = Levers & Cause-Effect Chain, 2 = Long-Term Trend Charts
    var dockMode by remember { mutableIntStateOf(1) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(skin.background),
    ) {
        // Primary Anatomical Viewport — occupies majority of screen so body parts are large & clear
        Box(
            modifier = Modifier
                .weight(if (dockMode == 0) 1f else 0.64f)
                .fillMaxWidth(),
        ) {
            InteractiveAnatomyStage(
                state = state,
                selectedRegion = selectedRegion,
                onSelectRegion = viewModel::selectRegion,
            )

            // Critical Warning Pill when any parameter crosses critical threshold
            if (state.criticalCount > 0) {
                Surface(
                    color = skin.criticalColor.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .testTag("critical_alert_pill"),
                ) {
                    Text(
                        text = "${state.criticalCount} CRITICAL THRESHOLD${if (state.criticalCount > 1) "S" else ""}",
                        style = TelemetryNumericStyle.copy(
                            fontSize = MaterialTheme.typography.labelMedium.fontSize,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }

        // Bottom Telemetry & Cause-Effect Dock
        Surface(
            color = skin.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, skin.borderSubtle, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Live Running Number Strip + Playback Speed Controls
                LiveVitalStripAndPlayback(
                    state = state,
                    dockMode = dockMode,
                    onToggleDock = { dockMode = if (dockMode == 0) 1 else 0 },
                    onToggleCharts = { dockMode = if (dockMode == 2) 1 else 2 },
                    onTogglePlay = viewModel::togglePlaying,
                    onReset = viewModel::resetSimulation,
                    onSpeedChange = viewModel::setTimeSpeed,
                    onSaveSnapshot = viewModel::saveCurrentSimulationRecord,
                    isSaving = isSaving,
                    skin = skin,
                )

                if (dockMode == 1) {
                    // Searchable 114-Ingredient Dropdown + Live Physiological Manipulation
                    LeverControlPanel(
                        state = state,
                        activeIngredient = activeIngredient,
                        onSelectIngredient = viewModel::selectIngredient,
                        onChangeValue = viewModel::updateActiveIngredientValue,
                        onStep = viewModel::stepActiveIngredient,
                        skin = skin,
                    )

                    CausalChainStrip(
                        chain = state.causalChain,
                        selectedRegion = selectedRegion,
                        onTapNode = { node -> viewModel.selectRegion(node.targetRegion) },
                        skin = skin,
                    )
                } else if (dockMode == 2) {
                    TrendChartsPanel(state = state, skin = skin)
                }
            }
        }
    }
}

@Composable
private fun LiveVitalStripAndPlayback(
    state: PhysiologyState,
    dockMode: Int,
    onToggleDock: () -> Unit,
    onToggleCharts: () -> Unit,
    onTogglePlay: () -> Unit,
    onReset: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSaveSnapshot: () -> Unit,
    isSaving: Boolean,
    skin: BioSkinPalette,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Real-time running tabular numerals strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val metricsToDisplay = listOf("glucose", "potassium", "hr", "map", "egfr", "spo2", "ph")
            metricsToDisplay.forEach { key ->
                val m = state.metrics[key] ?: return@forEach
                val badgeColor by animateColorAsState(
                    targetValue = when (m.severity) {
                        SeverityLevel.NORMAL -> skin.normalColor
                        SeverityLevel.CAUTION -> skin.cautionColor
                        SeverityLevel.CRITICAL -> skin.criticalColor
                    },
                    label = "vital_color_$key",
                )
                Surface(
                    color = skin.surfaceElevated,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) {
                        Text(
                            text = m.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.textSecondary,
                        )
                        Text(
                            text = "${PhysiologyEngine.formatCompact(m.value)} ${m.unit}",
                            style = TelemetryNumericStyle.copy(
                                fontSize = MaterialTheme.typography.labelLarge.fontSize,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = badgeColor,
                        )
                    }
                }
            }
        }

        // Playback controls & speed multiplier chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.size(38.dp).testTag("play_pause_button"),
                ) {
                    Icon(
                        imageVector = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "Pause simulation" else "Play simulation",
                        tint = skin.primaryAccent,
                    )
                }
                IconButton(
                    onClick = onReset,
                    modifier = Modifier.size(38.dp).testTag("reset_sim_button"),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reset simulation", tint = skin.textSecondary)
                }
                val speeds = listOf(1f, 10f, 60f, 300f)
                speeds.forEach { spd ->
                    val active = state.timeSpeedMultiplier == spd
                    Surface(
                        color = if (active) skin.primaryAccent else skin.surfaceElevated,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .clickable { onSpeedChange(spd) }
                            .padding(horizontal = 1.dp)
                            .testTag("speed_${spd.toInt()}x"),
                    ) {
                        Text(
                            text = "${spd.toInt()}x",
                            style = TelemetryNumericStyle.copy(
                                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = if (active) skin.onAccent else skin.textSecondary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onSaveSnapshot,
                    enabled = !isSaving,
                    modifier = Modifier.size(38.dp).testTag("save_snapshot_button"),
                ) {
                    Icon(Icons.Filled.Save, contentDescription = "Save snapshot to encrypted cloud", tint = skin.secondaryAccent)
                }
                IconButton(
                    onClick = onToggleCharts,
                    modifier = Modifier.size(38.dp).testTag("toggle_charts_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ShowChart,
                        contentDescription = "Toggle trend charts",
                        tint = if (dockMode == 2) skin.primaryAccent else skin.textSecondary,
                    )
                }
                IconButton(
                    onClick = onToggleDock,
                    modifier = Modifier.size(38.dp).testTag("toggle_dock_button"),
                ) {
                    Icon(
                        imageVector = if (dockMode == 0) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = "Expand or collapse controls",
                        tint = skin.textPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun LeverControlPanel(
    state: PhysiologyState,
    activeIngredient: BiochemicalIngredient,
    onSelectIngredient: (BiochemicalIngredient) -> Unit,
    onChangeValue: (Double) -> Unit,
    onStep: (Int) -> Unit,
    skin: BioSkinPalette,
) {
    var dropdownOpen by remember { mutableStateOf(false) }

    val currentVal = PhysiologyEngine.getIngredientValue(state, activeIngredient)
    val metricKey = when (activeIngredient.coreParam) {
        ManipulableParam.GLUCOSE -> "glucose"
        ManipulableParam.INSULIN -> "insulin"
        ManipulableParam.SODIUM -> "sodium"
        ManipulableParam.POTASSIUM -> "potassium"
        ManipulableParam.HYDRATION -> "map"
        ManipulableParam.SYMPATHETIC -> "hr"
        ManipulableParam.OXYGEN_INFLOW -> "spo2"
        null -> null
    }
    val readout = metricKey?.let { state.metrics[it] }
    val subtitleTxt = if (readout != null) {
        val rateTxt = "dX/dt ${PhysiologyEngine.formatSigned(readout.ratePerMin)}/min"
        val etaTxt = readout.etaToCriticalSec?.let { " • Crit in ${PhysiologyEngine.formatDuration(it)}" } ?: " • Stable"
        "$rateTxt$etaTxt"
    } else {
        "Norm ${activeIngredient.normalRange} • ${activeIngredient.targetRegion.displayName}"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Single Searchable Dropdown Trigger (replaces side-by-side ingredient buttons)
        Surface(
            color = skin.surfaceElevated,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .border(1.dp, skin.primaryAccent.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .clickable { dropdownOpen = true }
                .testTag("ingredient_dropdown_button"),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        color = categoryBadgeColor(activeIngredient.category, skin).copy(alpha = 0.18f),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = activeIngredient.category.badge,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = categoryBadgeColor(activeIngredient.category, skin),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                    Text(
                        text = activeIngredient.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = skin.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "${IngredientCatalog.all.size} items",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textSecondary,
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = "Open ingredients list",
                        tint = skin.primaryAccent,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${activeIngredient.name}: ${PhysiologyEngine.formatCompact(currentVal)} ${activeIngredient.unit}",
                    style = TelemetryNumericStyle.copy(
                        fontSize = MaterialTheme.typography.titleSmall.fontSize,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = skin.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitleTxt,
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.primaryAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onStep(-1) },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(skin.surfaceElevated)
                        .testTag("lever_decrease_button"),
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = "Decrease level", tint = skin.textPrimary)
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { onStep(+1) },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(skin.primaryAccent)
                        .testTag("lever_increase_button"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Increase level", tint = skin.onAccent)
                }
            }
        }

        Slider(
            value = currentVal.toFloat().coerceIn(activeIngredient.minVal.toFloat(), activeIngredient.maxVal.toFloat()),
            onValueChange = { onChangeValue(it.toDouble()) },
            valueRange = activeIngredient.minVal.toFloat()..activeIngredient.maxVal.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = skin.primaryAccent,
                activeTrackColor = skin.primaryAccent,
                inactiveTrackColor = skin.borderSubtle,
            ),
            modifier = Modifier.fillMaxWidth().height(26.dp).testTag("lever_slider"),
        )
    }

    if (dropdownOpen) {
        IngredientSearchDropdownDialog(
            activeIngredient = activeIngredient,
            state = state,
            skin = skin,
            onDismiss = { dropdownOpen = false },
            onSelect = { chosen ->
                onSelectIngredient(chosen)
                dropdownOpen = false
            },
        )
    }
}

@Composable
private fun IngredientSearchDropdownDialog(
    activeIngredient: BiochemicalIngredient,
    state: PhysiologyState,
    skin: BioSkinPalette,
    onDismiss: () -> Unit,
    onSelect: (BiochemicalIngredient) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) { IngredientCatalog.search(query) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = skin.surface,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 20.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 28.dp)
                .heightIn(max = 560.dp)
                .imePadding()
                .border(1.5.dp, skin.primaryAccent.copy(alpha = 0.55f), RoundedCornerShape(20.dp)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = "Physiological Ingredients (${IngredientCatalog.all.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = skin.textPrimary,
                        )
                        Text(
                            text = "Metals, vitamins, electrolytes, hormones & metabolites",
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.textSecondary,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close ingredients list", tint = skin.textSecondary)
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = skin.primaryAccent)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = skin.textSecondary)
                            }
                        }
                    },
                    placeholder = {
                        Text(
                            text = "Search iron, vitamin D3, zinc, cortisol, K+...",
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textSecondary,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = skin.primaryAccent,
                        unfocusedBorderColor = skin.borderSubtle,
                        focusedTextColor = skin.textPrimary,
                        unfocusedTextColor = skin.textPrimary,
                        cursorColor = skin.primaryAccent,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ingredient_search_input"),
                )

                Text(
                    text = "Showing ${filtered.size} of ${IngredientCatalog.all.size} ingredients",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textSecondary,
                )

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No matching ingredient. Try a metal, vitamin, or organ name.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = skin.textSecondary,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            val isSelected = item.id == activeIngredient.id
                            val itemVal = PhysiologyEngine.getIngredientValue(state, item)
                            val badgeColor = categoryBadgeColor(item.category, skin)
                            Surface(
                                color = if (isSelected) skin.primaryAccent.copy(alpha = 0.16f) else skin.surfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) skin.primaryAccent else skin.borderSubtle,
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    .clickable { onSelect(item) }
                                    .testTag("ingredient_item_${item.id}"),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Surface(
                                                color = badgeColor.copy(alpha = 0.16f),
                                                shape = RoundedCornerShape(5.dp),
                                            ) {
                                                Text(
                                                    text = item.category.badge,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = badgeColor,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                )
                                            }
                                            Text(
                                                text = item.name,
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = skin.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            text = "${item.targetRegion.displayName} • Norm ${item.normalRange}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = skin.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = item.mechanism,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = skin.textSecondary.copy(alpha = 0.88f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "${PhysiologyEngine.formatCompact(itemVal)} ${item.unit}",
                                            style = TelemetryNumericStyle.copy(
                                                fontSize = MaterialTheme.typography.labelMedium.fontSize,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                            color = if (isSelected) skin.primaryAccent else skin.textPrimary,
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = "Selected",
                                                tint = skin.primaryAccent,
                                                modifier = Modifier.size(16.dp),
                                            )
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
}

private fun categoryBadgeColor(category: IngredientCategory, skin: BioSkinPalette): Color = when (category) {
    IngredientCategory.ELECTROLYTE_GAS -> skin.primaryAccent
    IngredientCategory.METAL -> skin.cautionColor
    IngredientCategory.VITAMIN -> skin.normalColor
    IngredientCategory.AMINO_NEURO -> skin.secondaryAccent
    IngredientCategory.HORMONE_PEPTIDE -> skin.arterialRed
    IngredientCategory.METABOLITE_CLINICAL -> skin.primaryAccent
}

@Composable
private fun CausalChainStrip(
    chain: List<CausalNode>,
    selectedRegion: BodyRegion?,
    onTapNode: (CausalNode) -> Unit,
    skin: BioSkinPalette,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Timeline, contentDescription = null, tint = skin.primaryAccent, modifier = Modifier.size(15.dp))
            Text(
                text = "CAUSE → EFFECT TIMELINE (EXACT MATH & ONSET)",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = skin.textSecondary,
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 8.dp),
        ) {
            items(chain, key = { it.id }) { node ->
                val sevColor = when (node.severity) {
                    SeverityLevel.NORMAL -> skin.normalColor
                    SeverityLevel.CAUTION -> skin.cautionColor
                    SeverityLevel.CRITICAL -> skin.criticalColor
                }
                val highlighted = selectedRegion == node.targetRegion || selectedRegion == node.sourceRegion
                Surface(
                    color = skin.surfaceElevated,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .width(232.dp)
                        .border(
                            width = if (highlighted) 1.5.dp else 1.dp,
                            color = if (highlighted) sevColor else skin.borderSubtle,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { onTapNode(node) }
                        .testTag("causal_node_${node.stepOrder}"),
                ) {
                    Column(modifier = Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "#${node.stepOrder} T+${PhysiologyEngine.formatDuration(node.onsetTimeSec)}",
                                style = TelemetryNumericStyle.copy(
                                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = sevColor,
                            )
                            Text(
                                text = "${node.sourceRegion.systemTag} → ${node.targetRegion.displayName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = skin.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = node.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = skin.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = node.equationSummary,
                            style = TelemetryNumericStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                            color = skin.primaryAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = node.numericDelta,
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendChartsPanel(
    state: PhysiologyState,
    skin: BioSkinPalette,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "LONG-TERM PHYSIOLOGICAL TRENDS (${state.history.size} SAMPLES)",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = skin.textSecondary,
            )
            Text(
                text = "Blue: Glucose | Coral: HR | Amber: MAP | Green: K⁺",
                style = MaterialTheme.typography.labelSmall,
                color = skin.primaryAccent,
            )
        }

        Surface(
            color = skin.surfaceElevated,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(155.dp)
                .border(1.dp, skin.borderSubtle, RoundedCornerShape(14.dp)),
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                val samples = state.history
                if (samples.size < 2) return@Canvas

                fun drawSeries(values: List<Double>, minV: Double, maxV: Double, color: Color) {
                    val path = Path()
                    val span = (maxV - minV).coerceAtLeast(1.0)
                    values.forEachIndexed { idx, v ->
                        val x = (idx.toFloat() / (values.size - 1)) * size.width
                        val norm = ((v - minV) / span).toFloat().coerceIn(0f, 1f)
                        val y = size.height * (1f - norm)
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path = path, color = color, style = Stroke(width = 3.2f))
                }

                // Baseline horizontal grid lines
                for (i in 0..3) {
                    val gy = size.height * (i / 3f)
                    drawLine(skin.borderSubtle.copy(alpha = 0.4f), Offset(0f, gy), Offset(size.width, gy), 1f)
                }

                drawSeries(samples.map { it.glucoseMgDl }, 40.0, 350.0, skin.primaryAccent)
                drawSeries(samples.map { it.heartRateBpm }, 35.0, 180.0, skin.arterialRed)
                drawSeries(samples.map { it.mapMmHg }, 45.0, 160.0, skin.cautionColor)
                drawSeries(samples.map { it.potassiumMeqL * 25.0 }, 50.0, 190.0, skin.normalColor)
            }
        }
    }
}
