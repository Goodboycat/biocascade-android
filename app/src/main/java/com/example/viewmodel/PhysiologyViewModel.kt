package com.example.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ClinicalRecordsRepository
import com.example.data.EncryptedPdfExporter
import com.example.data.PreferencesRepository
import com.example.medicalweb.MedicalSearchUiState
import com.example.medicalweb.MedicalWebRepository
import com.example.physiology.BiochemicalIngredient
import com.example.physiology.BodyRegion
import com.example.physiology.HumanBaselineProfile
import com.example.physiology.IngredientCatalog
import com.example.physiology.ManipulableParam
import com.example.physiology.PhysiologyEngine
import com.example.physiology.PhysiologyState
import com.example.physiology.SimulationRecord
import com.example.physiology.HipaaAuditEntry
import com.example.ui.theme.DashboardSkin
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

class PhysiologyViewModel(
    private val recordsRepository: ClinicalRecordsRepository,
    private val medicalWebRepository: MedicalWebRepository,
    private val preferencesRepository: PreferencesRepository,
    private val pdfExporter: EncryptedPdfExporter,
    private val currentUserId: String,
) : ViewModel() {

    private val _physiologyState = MutableStateFlow(
        PhysiologyEngine.initialState(HumanBaselineProfile(userId = currentUserId))
    )
    val physiologyState: StateFlow<PhysiologyState> = _physiologyState.asStateFlow()

    private val _selectedRegion = MutableStateFlow<BodyRegion?>(null)
    val selectedRegion: StateFlow<BodyRegion?> = _selectedRegion.asStateFlow()

    private val _activeLever = MutableStateFlow(ManipulableParam.GLUCOSE)
    val activeLever: StateFlow<ManipulableParam> = _activeLever.asStateFlow()

    private val _activeIngredient = MutableStateFlow(IngredientCatalog.defaultIngredient)
    val activeIngredient: StateFlow<BiochemicalIngredient> = _activeIngredient.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _medicalSearchState = MutableStateFlow<MedicalSearchUiState>(MedicalSearchUiState.Idle)
    val medicalSearchState: StateFlow<MedicalSearchUiState> = _medicalSearchState.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    val selectedSkin: StateFlow<DashboardSkin> = preferencesRepository.selectedSkin
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = DashboardSkin.CLINICAL_OBSIDIAN,
        )

    val hipaaLockEnabled: StateFlow<Boolean> = preferencesRepository.hipaaSessionLockEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = false,
        )

    val profilesState: StateFlow<UiState<List<HumanBaselineProfile>>> =
        recordsRepository.observeProfiles(currentUserId)
            .map<List<HumanBaselineProfile>, UiState<List<HumanBaselineProfile>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing profiles", rootError)
                emit(UiState.Error(rootError.localizedMessage ?: "Failed to load baseline profiles"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading,
            )

    val recordsState: StateFlow<UiState<List<SimulationRecord>>> =
        recordsRepository.observeRecords(currentUserId)
            .map<List<SimulationRecord>, UiState<List<SimulationRecord>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing records", rootError)
                emit(UiState.Error(rootError.localizedMessage ?: "Failed to load clinical records"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading,
            )

    val auditLogsState: StateFlow<UiState<List<HipaaAuditEntry>>> =
        recordsRepository.observeAuditLogs(currentUserId)
            .map<List<HipaaAuditEntry>, UiState<List<HipaaAuditEntry>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing audit logs", rootError)
                emit(UiState.Error(rootError.localizedMessage ?: "Failed to load audit log"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading,
            )

    init {
        startSimulationLoop()
    }

    private fun startSimulationLoop() {
        viewModelScope.launch {
            while (isActive) {
                delay(50L)
                _physiologyState.update { current ->
                    if (!current.playing) current
                    else {
                        val dtSim = 0.05 * current.timeSpeedMultiplier.toDouble()
                        PhysiologyEngine.stepSimulation(current, dtSim)
                    }
                }
            }
        }
    }

    fun togglePlaying() {
        _physiologyState.update { it.copy(playing = !it.playing) }
    }

    fun setTimeSpeed(multiplier: Float) {
        _physiologyState.update { it.copy(timeSpeedMultiplier = multiplier) }
    }

    fun resetSimulation() {
        _physiologyState.update {
            PhysiologyEngine.withActiveIngredient(
                PhysiologyEngine.initialState(it.profile),
                _activeIngredient.value,
            )
        }
    }

    fun selectRegion(region: BodyRegion?) {
        _selectedRegion.value = region
    }

    fun selectLever(param: ManipulableParam) {
        val ing = IngredientCatalog.forCoreParam(param)
        _activeLever.value = param
        _activeIngredient.value = ing
        _selectedRegion.value = param.primaryRegion
        _physiologyState.update { PhysiologyEngine.withActiveIngredient(it, ing) }
    }

    fun selectIngredient(ingredient: BiochemicalIngredient) {
        _activeIngredient.value = ingredient
        ingredient.coreParam?.let { _activeLever.value = it }
        _selectedRegion.value = ingredient.targetRegion
        _physiologyState.update { PhysiologyEngine.withActiveIngredient(it, ingredient) }
    }

    fun updateParamValue(param: ManipulableParam, newValue: Double) {
        _activeLever.value = param
        _physiologyState.update { current ->
            PhysiologyEngine.applyImmediateParamDelta(current, param, newValue)
        }
    }

    fun updateActiveIngredientValue(newValue: Double) {
        val ing = _activeIngredient.value
        ing.coreParam?.let { _activeLever.value = it }
        _physiologyState.update { current ->
            PhysiologyEngine.applyIngredientValue(current, ing, newValue)
        }
    }

    fun stepParam(param: ManipulableParam, deltaSign: Int) {
        val currentVal = getParamValue(_physiologyState.value, param)
        updateParamValue(param, currentVal + deltaSign * param.step)
    }

    fun stepActiveIngredient(deltaSign: Int) {
        val ing = _activeIngredient.value
        val currentVal = PhysiologyEngine.getIngredientValue(_physiologyState.value, ing)
        updateActiveIngredientValue(currentVal + deltaSign * ing.step)
    }

    fun getParamValue(state: PhysiologyState, param: ManipulableParam): Double = when (param) {
        ManipulableParam.GLUCOSE -> state.glucoseMgDl
        ManipulableParam.INSULIN -> state.insulinUiMl
        ManipulableParam.SODIUM -> state.sodiumMeqL
        ManipulableParam.POTASSIUM -> state.potassiumMeqL
        ManipulableParam.HYDRATION -> state.hydrationPct
        ManipulableParam.SYMPATHETIC -> state.sympatheticDrivePct
        ManipulableParam.OXYGEN_INFLOW -> state.fiO2Pct
    }

    fun applyBaselineProfile(profile: HumanBaselineProfile) {
        val clean = profile.copy(userId = currentUserId)
        _physiologyState.update { PhysiologyEngine.initialState(clean) }
    }

    fun saveBaselineProfile(profile: HumanBaselineProfile) {
        viewModelScope.launch {
            _isSaving.value = true
            val clean = profile.copy(userId = currentUserId)
            val result = recordsRepository.saveProfile(currentUserId, clean)
            _isSaving.value = false
            result.fold(
                onSuccess = {
                    _physiologyState.update { PhysiologyEngine.initialState(clean) }
                    _statusMessage.value = "Baseline profile encrypted & synced."
                },
                onFailure = { err ->
                    _statusMessage.value = "Profile sync error: ${err.localizedMessage ?: "Permission denied"}"
                },
            )
        }
    }

    fun saveCurrentSimulationRecord() {
        viewModelScope.launch {
            _isSaving.value = true
            val result = recordsRepository.saveSimulationSnapshot(currentUserId, _physiologyState.value)
            _isSaving.value = false
            result.fold(
                onSuccess = { _statusMessage.value = "Simulation record synced & cached with AES-256-GCM." },
                onFailure = { err -> _statusMessage.value = "Record save failed: ${err.localizedMessage}" },
            )
        }
    }

    fun deleteRecord(recordId: String) {
        viewModelScope.launch {
            val result = recordsRepository.deleteRecord(currentUserId, recordId)
            result.onFailure { err ->
                _statusMessage.value = "Delete failed: ${err.localizedMessage}"
            }
        }
    }

    fun exportEncryptedPdf(destinationUri: Uri, passphrase: String) {
        viewModelScope.launch {
            _isSaving.value = true
            val state = _physiologyState.value
            val result = pdfExporter.exportEncryptedPdfToUri(destinationUri, state, passphrase)
            _isSaving.value = false
            result.fold(
                onSuccess = { msg ->
                    recordsRepository.logAuditEvent(
                        currentUserId,
                        "PDF_EXPORTED",
                        "128-bit password-encrypted clinical PDF exported (${state.profile.label})",
                    )
                    _statusMessage.value = msg
                },
                onFailure = { err ->
                    _statusMessage.value = "PDF export failed: ${err.localizedMessage}"
                },
            )
        }
    }

    fun setSkin(skin: DashboardSkin) {
        viewModelScope.launch {
            preferencesRepository.setSelectedSkin(skin)
        }
    }

    fun setHipaaLock(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setHipaaSessionLock(enabled)
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun runMedicalSearch(queryOverride: String? = null) {
        val target = (queryOverride ?: _searchQuery.value).trim()
        _searchQuery.value = target
        if (target.isBlank()) return
        viewModelScope.launch {
            _medicalSearchState.value = MedicalSearchUiState.Loading
            val res = medicalWebRepository.searchMedicalSummaries(target)
            res.fold(
                onSuccess = { (medline, pubmed) ->
                    _medicalSearchState.value = MedicalSearchUiState.Success(
                        query = target,
                        medlineResults = medline,
                        pubmedResults = pubmed,
                    )
                },
                onFailure = { err ->
                    _medicalSearchState.value = MedicalSearchUiState.Error(
                        err.localizedMessage ?: "Unable to fetch NIH MedlinePlus/PubMed summaries."
                    )
                },
            )
        }
    }

    fun hasLocalEncryptedVault(): Boolean = recordsRepository.hasLocalVault()

    fun clearLocalVault() {
        viewModelScope.launch {
            recordsRepository.clearLocalVault()
            recordsRepository.logAuditEvent(currentUserId, "DATA_CLEARED", "Local AES-256-GCM vault cleared")
            _statusMessage.value = "Local encrypted cache cleared."
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    private companion object {
        const val TAG = "PhysiologyVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
