package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.medicalweb.MedicalSearchUiState
import com.example.medicalweb.MedicalSource
import com.example.medicalweb.MedicalSummaryItem
import com.example.physiology.ManipulableParam
import com.example.ui.theme.BioSkinPalette
import com.example.ui.theme.LocalBioSkin
import com.example.ui.theme.TelemetryNumericStyle

private val quickTopics = listOf(
    "Hyperkalemia",
    "Insulin Resistance",
    "Diabetic Ketoacidosis",
    "Glomerular Filtration Rate",
    "Hyponatremia",
    "Hypoxemia",
)

@Composable
fun MedSearchScreen(
    query: String,
    searchState: MedicalSearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: (String?) -> Unit,
    onJumpToParam: (ManipulableParam) -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = LocalBioSkin.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(skin.background)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "NIH MedlinePlus & PubMed Direct Summaries",
            style = MaterialTheme.typography.titleMedium,
            color = skin.textPrimary,
        )

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    text = stringResource(R.string.search_placeholder),
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textSecondary,
                )
            },
            singleLine = true,
            trailingIcon = {
                IconButton(
                    onClick = { onSearch(null) },
                    modifier = Modifier.testTag("med_search_submit"),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "Search medical databases", tint = skin.primaryAccent)
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(null) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = skin.primaryAccent,
                unfocusedBorderColor = skin.borderSubtle,
                focusedTextColor = skin.textPrimary,
                unfocusedTextColor = skin.textPrimary,
                focusedContainerColor = skin.surface,
                unfocusedContainerColor = skin.surface,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("med_search_input"),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 6.dp),
        ) {
            items(quickTopics) { topic ->
                Surface(
                    color = skin.surfaceElevated,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .border(1.dp, skin.borderSubtle, RoundedCornerShape(10.dp))
                        .clickable { onSearch(topic) }
                        .testTag("topic_${topic.lowercase().replace(" ", "_")}"),
                ) {
                    Text(
                        text = topic,
                        style = MaterialTheme.typography.labelMedium,
                        color = skin.primaryAccent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }

        when (searchState) {
            MedicalSearchUiState.Idle -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Biotech,
                            contentDescription = null,
                            tint = skin.primaryAccent.copy(alpha = 0.7f),
                            modifier = Modifier.size(44.dp),
                        )
                        Text(
                            text = "Direct Structured Parser (No Web Browser)",
                            style = MaterialTheme.typography.titleSmall,
                            color = skin.textPrimary,
                        )
                        Text(
                            text = "Queries NIH MedlinePlus XML & NCBI PubMed E-utilities to extract quantitative thresholds, clinical mechanisms, and peer-reviewed findings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textSecondary,
                        )
                    }
                }
            }

            MedicalSearchUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = skin.primaryAccent)
                        Text("Parsing NIH MedlinePlus & PubMed XML...", style = MaterialTheme.typography.bodySmall, color = skin.textSecondary)
                    }
                }
            }

            is MedicalSearchUiState.Error -> {
                Surface(
                    color = skin.surface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, skin.criticalColor.copy(alpha = 0.6f), RoundedCornerShape(14.dp)),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Lookup Issue", style = MaterialTheme.typography.titleSmall, color = skin.criticalColor)
                        Text(searchState.message, style = MaterialTheme.typography.bodySmall, color = skin.textPrimary)
                    }
                }
            }

            is MedicalSearchUiState.Success -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 18.dp),
                ) {
                    if (searchState.medlineResults.isNotEmpty()) {
                        item {
                            Text(
                                text = "MEDLINEPLUS CLINICAL SUMMARIES (${searchState.medlineResults.size})",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = skin.secondaryAccent,
                            )
                        }
                        items(searchState.medlineResults, key = { it.id }) { item ->
                            MedicalSummaryCard(item = item, skin = skin, onJumpToParam = onJumpToParam)
                        }
                    }

                    if (searchState.pubmedResults.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "PUBMED PEER-REVIEWED ABSTRACTS (${searchState.pubmedResults.size})",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = skin.primaryAccent,
                            )
                        }
                        items(searchState.pubmedResults, key = { it.id }) { item ->
                            MedicalSummaryCard(item = item, skin = skin, onJumpToParam = onJumpToParam)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MedicalSummaryCard(
    item: MedicalSummaryItem,
    skin: BioSkinPalette,
    onJumpToParam: (ManipulableParam) -> Unit,
) {
    val accent = if (item.source == MedicalSource.MEDLINE_PLUS) skin.secondaryAccent else skin.primaryAccent
    Surface(
        color = skin.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, skin.borderSubtle, RoundedCornerShape(14.dp)),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (item.source == MedicalSource.MEDLINE_PLUS) Icons.Filled.MenuBook else Icons.Filled.Biotech,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = skin.textPrimary,
            )

            if (item.extractedParameters.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.extractedParameters.forEach { param ->
                        Surface(
                            color = skin.surfaceElevated,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                        ) {
                            Text(
                                text = param,
                                style = TelemetryNumericStyle.copy(
                                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = accent,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            item.summaryBullets.forEach { bullet ->
                Text(
                    text = "• $bullet",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textSecondary,
                )
            }

            if (item.linkedParam != null) {
                Button(
                    onClick = { onJumpToParam(item.linkedParam) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = skin.surfaceElevated,
                        contentColor = skin.primaryAccent,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Text(
                        text = "Adjust ${item.linkedParam.shortName} in Body Lab",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
