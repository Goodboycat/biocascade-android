package com.example.medicalweb

import com.example.physiology.ManipulableParam

enum class MedicalSource {
    MEDLINE_PLUS,
    PUBMED,
}

data class MedicalSummaryItem(
    val id: String,
    val source: MedicalSource,
    val title: String,
    val subtitle: String,
    val summaryBullets: List<String>,
    val extractedParameters: List<String>,
    val referenceUrl: String,
    val linkedParam: ManipulableParam?,
)

sealed interface MedicalSearchUiState {
    data object Idle : MedicalSearchUiState
    data object Loading : MedicalSearchUiState
    data class Success(
        val query: String,
        val medlineResults: List<MedicalSummaryItem>,
        val pubmedResults: List<MedicalSummaryItem>,
    ) : MedicalSearchUiState
    data class Error(val message: String) : MedicalSearchUiState
}
