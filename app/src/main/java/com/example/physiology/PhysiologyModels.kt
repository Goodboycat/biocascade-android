package com.example.physiology

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class BiologicalSex(val label: String) {
    MALE("Male"),
    FEMALE("Female");

    companion object {
        fun fromRaw(raw: String?): BiologicalSex =
            entries.firstOrNull { it.name == raw } ?: MALE
    }
}

enum class SeverityLevel(val label: String) {
    NORMAL("NORMAL"),
    CAUTION("CAUTION"),
    CRITICAL("CRITICAL"),
}

enum class BodyRegion(
    val displayName: String,
    val systemTag: String,
    val normX: Float,
    val normY: Float,
) {
    BRAIN("Brain / CNS", "Neurovascular", 0.50f, 0.11f),
    LUNGS("Lungs & Pleura", "Respiratory", 0.50f, 0.29f),
    HEART("Heart & Aorta", "Cardiovascular", 0.52f, 0.33f),
    LIVER_PANCREAS("Liver & Pancreas", "Metabolic", 0.45f, 0.43f),
    DIGESTIVE_GI("GI Tract & Stomach", "Gastrointestinal", 0.54f, 0.49f),
    KIDNEYS("Renal Cortex", "Renal & Electrolytes", 0.50f, 0.49f),
    VESSELS("Systemic Vessels", "Vascular Tree", 0.34f, 0.61f),
}

enum class ManipulableParam(
    val displayName: String,
    val shortName: String,
    val unit: String,
    val minVal: Double,
    val maxVal: Double,
    val step: Double,
    val primaryRegion: BodyRegion,
) {
    GLUCOSE("Blood Glucose", "Glucose", "mg/dL", 35.0, 480.0, 10.0, BodyRegion.LIVER_PANCREAS),
    INSULIN("Serum Insulin", "Insulin", "μIU/mL", 1.0, 140.0, 4.0, BodyRegion.LIVER_PANCREAS),
    SODIUM("Serum Sodium (Na⁺)", "Na⁺", "mEq/L", 115.0, 165.0, 1.5, BodyRegion.KIDNEYS),
    POTASSIUM("Serum Potassium (K⁺)", "K⁺", "mEq/L", 2.2, 7.6, 0.2, BodyRegion.HEART),
    HYDRATION("Blood Volume / Hydration", "Volume", "%", 72.0, 128.0, 2.5, BodyRegion.VESSELS),
    SYMPATHETIC("Sympathetic Drive", "Symp", "%", 40.0, 220.0, 10.0, BodyRegion.HEART),
    OXYGEN_INFLOW("Inspired O₂ Fraction", "FiO₂", "%", 14.0, 60.0, 2.0, BodyRegion.LUNGS),
}

data class DerivedBiometrics(
    val bmi: Double,
    val bsaM2: Double,
    val totalBloodVolumeMl: Double,
    val totalBodyWaterL: Double,
    val baselineEgfr: Double,
    val restingMapMmHg: Double,
    val restingStrokeVolumeMl: Double,
    val restingCardiacOutputLMin: Double,
)

@Keep
@IgnoreExtraProperties
data class HumanBaselineProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val label: String = "Primary Adult Baseline",
    val ageYears: Long = 32L,
    val biologicalSex: String = BiologicalSex.MALE.name,
    val weightKg: Double = 72.0,
    val heightCm: Double = 175.0,
    val serumCreatinineMgDl: Double = 0.95,
    val restingHeartRateBpm: Long = 68L,
    val restingSystolicMmHg: Long = 118L,
    val restingDiastolicMmHg: Long = 76L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun derivedStats(): DerivedBiometrics {
        val safeWeight = weightKg.coerceIn(20.0, 300.0)
        val safeHeightCm = heightCm.coerceIn(80.0, 250.0)
        val heightM = safeHeightCm / 100.0
        val bmi = safeWeight / (heightM * heightM)
        // Mosteller BSA formula
        val bsa = sqrt((safeHeightCm * safeWeight) / 3600.0)
        val sex = BiologicalSex.fromRaw(biologicalSex)
        // Nadler's blood volume equation (L -> mL)
        val bloodVolL = if (sex == BiologicalSex.MALE) {
            0.3669 * heightM.pow(3) + 0.03219 * safeWeight + 0.6041
        } else {
            0.3561 * heightM.pow(3) + 0.03308 * safeWeight + 0.1833
        }
        val tbwL = if (sex == BiologicalSex.MALE) 0.60 * safeWeight else 0.50 * safeWeight
        // 2021 CKD-EPI race-agnostic eGFR
        val scr = serumCreatinineMgDl.coerceIn(0.2, 15.0)
        val kappa = if (sex == BiologicalSex.FEMALE) 0.7 else 0.9
        val alpha = if (sex == BiologicalSex.FEMALE) -0.329 else -0.411
        val sexFactor = if (sex == BiologicalSex.FEMALE) 1.018 else 1.0
        val ratio = scr / kappa
        val egfr = 141.0 * min(ratio, 1.0).pow(alpha) * max(ratio, 1.0).pow(-1.209) *
            0.993.pow(ageYears.coerceIn(1L, 120L).toDouble()) * sexFactor
        val map = restingDiastolicMmHg + (restingSystolicMmHg - restingDiastolicMmHg) / 3.0
        val sv = (bloodVolL * 1000.0) * 0.0135 // ~70mL for 5.2L blood volume
        val co = (sv * restingHeartRateBpm) / 1000.0
        return DerivedBiometrics(
            bmi = bmi,
            bsaM2 = bsa,
            totalBloodVolumeMl = bloodVolL * 1000.0,
            totalBodyWaterL = tbwL,
            baselineEgfr = egfr.coerceIn(5.0, 180.0),
            restingMapMmHg = map,
            restingStrokeVolumeMl = sv,
            restingCardiacOutputLMin = co,
        )
    }
}

@Keep
@IgnoreExtraProperties
data class SimulationRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val profileLabel: String = "Primary Adult Baseline",
    val stimulusSummary: String = "Baseline Homeostasis",
    val simElapsedSeconds: Double = 0.0,
    val glucoseMgDl: Double = 90.0,
    val insulinUiMl: Double = 8.0,
    val sodiumMeqL: Double = 140.0,
    val potassiumMeqL: Double = 4.1,
    val heartRateBpm: Long = 68L,
    val systolicBp: Long = 118L,
    val diastolicBp: Long = 76L,
    val egfrMlMin: Double = 104.0,
    val spO2Pct: Double = 98.2,
    val bloodPh: Double = 7.40,
    val criticalCount: Long = 0L,
    val encryptedPayloadHash: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class HipaaAuditEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val action: String = "RECORD_SYNCED",
    val detail: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
)

data class MetricReadout(
    val id: String,
    val label: String,
    val value: Double,
    val unit: String,
    val normalRange: String,
    val ratePerMin: Double,
    val halfLifeMin: Double?,
    val etaToCriticalSec: Double?,
    val thresholdLabel: String,
    val severity: SeverityLevel,
)

data class CausalNode(
    val id: String,
    val stepOrder: Int,
    val sourceRegion: BodyRegion,
    val targetRegion: BodyRegion,
    val title: String,
    val equationSummary: String,
    val numericDelta: String,
    val onsetTimeSec: Double,
    val severity: SeverityLevel,
    val symptoms: List<String>,
)

data class RegionTelemetry(
    val region: BodyRegion,
    val severity: SeverityLevel,
    val primaryNumber: String,
    val secondaryNumber: String,
    val rateText: String,
    val etaText: String,
    val symptoms: List<String>,
)

data class PhysiologySample(
    val elapsedSec: Double,
    val glucoseMgDl: Double,
    val potassiumMeqL: Double,
    val sodiumMeqL: Double,
    val heartRateBpm: Double,
    val mapMmHg: Double,
    val egfrMlMin: Double,
    val spO2Pct: Double,
)

data class LeverTargets(
    val glucoseOffsetMgDl: Double = 0.0,
    val insulinOffsetUiMl: Double = 0.0,
    val sodiumOffsetMeqL: Double = 0.0,
    val potassiumOffsetMeqL: Double = 0.0,
    val hydrationPct: Double = 100.0,
    val sympatheticDrivePct: Double = 100.0,
    val fiO2Pct: Double = 21.0,
)

data class PhysiologyState(
    val simElapsedSeconds: Double = 0.0,
    val timeSpeedMultiplier: Float = 1f,
    val playing: Boolean = true,
    val profile: HumanBaselineProfile = HumanBaselineProfile(),
    val levers: LeverTargets = LeverTargets(),
    val glucoseMgDl: Double = 90.0,
    val insulinUiMl: Double = 8.0,
    val sodiumMeqL: Double = 140.0,
    val potassiumMeqL: Double = 4.1,
    val hydrationPct: Double = 100.0,
    val sympatheticDrivePct: Double = 100.0,
    val fiO2Pct: Double = 21.0,
    val heartRateBpm: Double = 68.0,
    val systolicBp: Double = 118.0,
    val diastolicBp: Double = 76.0,
    val mapMmHg: Double = 90.0,
    val cardiacOutputLMin: Double = 4.9,
    val vascularResistanceFactor: Double = 1.0,
    val egfrMlMin: Double = 104.0,
    val respiratoryRateBpm: Double = 14.0,
    val paO2MmHg: Double = 96.0,
    val spO2Pct: Double = 98.2,
    val paCo2MmHg: Double = 40.0,
    val hco3MeqL: Double = 24.0,
    val bloodPh: Double = 7.40,
    val serumOsmolalityMOsm: Double = 289.0,
    val metrics: Map<String, MetricReadout> = emptyMap(),
    val regionTelemetry: Map<BodyRegion, RegionTelemetry> = emptyMap(),
    val causalChain: List<CausalNode> = emptyList(),
    val history: List<PhysiologySample> = emptyList(),
    val stimulusSummary: String = "Baseline Homeostasis",
    val criticalCount: Int = 0,
    val ingredientLevels: Map<String, Double> = emptyMap(),
)
