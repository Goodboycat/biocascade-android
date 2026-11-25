package com.example.physiology

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Deterministic multi-system coupled ODE physiology engine.
 * Grounded in Bergman glucose-insulin kinetics, 2021 CKD-EPI renal clearance,
 * Nadler blood volume, Baroreceptor MAP-HR reflex, Hill O2 saturation, and
 * Henderson-Hasselbalch acid-base balance.
 */
object PhysiologyEngine {

    fun initialState(profile: HumanBaselineProfile): PhysiologyState {
        val derived = profile.derivedStats()
        val baseState = PhysiologyState(
            simElapsedSeconds = 0.0,
            timeSpeedMultiplier = 1f,
            playing = true,
            profile = profile,
            levers = LeverTargets(),
            glucoseMgDl = 90.0,
            insulinUiMl = 8.0,
            sodiumMeqL = 140.0,
            potassiumMeqL = 4.1,
            hydrationPct = 100.0,
            sympatheticDrivePct = 100.0,
            fiO2Pct = 21.0,
            heartRateBpm = profile.restingHeartRateBpm.toDouble(),
            systolicBp = profile.restingSystolicMmHg.toDouble(),
            diastolicBp = profile.restingDiastolicMmHg.toDouble(),
            mapMmHg = derived.restingMapMmHg,
            cardiacOutputLMin = derived.restingCardiacOutputLMin,
            vascularResistanceFactor = 1.0,
            egfrMlMin = derived.baselineEgfr,
            respiratoryRateBpm = 14.0,
            paO2MmHg = 96.0,
            spO2Pct = hillSpO2(96.0),
            paCo2MmHg = 40.0,
            hco3MeqL = 24.0,
            bloodPh = 7.40,
            serumOsmolalityMOsm = computeOsmolality(140.0, 90.0),
        )
        return enrichReadoutsAndChain(
            state = baseState,
            dGlucosePerMin = 0.0,
            dInsulinPerMin = 0.0,
            dSodiumPerMin = 0.0,
            dPotassiumPerMin = 0.0,
            dHrPerMin = 0.0,
            dMapPerMin = 0.0,
            dEgfrPerMin = 0.0,
            dSpO2PerMin = 0.0,
            dPhPerMin = 0.0,
            appendSample = true,
        )
    }

    fun applyImmediateParamDelta(
        current: PhysiologyState,
        param: ManipulableParam,
        newTargetValue: Double,
    ): PhysiologyState {
        val clamped = newTargetValue.coerceIn(param.minVal, param.maxVal)
        val levers = current.levers
        val updated = when (param) {
            ManipulableParam.GLUCOSE -> current.copy(
                glucoseMgDl = clamped,
                levers = levers.copy(glucoseOffsetMgDl = clamped - 90.0),
            )
            ManipulableParam.INSULIN -> current.copy(
                insulinUiMl = clamped,
                levers = levers.copy(insulinOffsetUiMl = clamped - 8.0),
            )
            ManipulableParam.SODIUM -> current.copy(
                sodiumMeqL = clamped,
                levers = levers.copy(sodiumOffsetMeqL = clamped - 140.0),
            )
            ManipulableParam.POTASSIUM -> current.copy(
                potassiumMeqL = clamped,
                levers = levers.copy(potassiumOffsetMeqL = clamped - 4.1),
            )
            ManipulableParam.HYDRATION -> current.copy(
                hydrationPct = clamped,
                levers = levers.copy(hydrationPct = clamped),
            )
            ManipulableParam.SYMPATHETIC -> current.copy(
                sympatheticDrivePct = clamped,
                levers = levers.copy(sympatheticDrivePct = clamped),
            )
            ManipulableParam.OXYGEN_INFLOW -> current.copy(
                fiO2Pct = clamped,
                levers = levers.copy(fiO2Pct = clamped),
            )
        }
        return stepSimulation(updated, dtSimSeconds = 0.05, forceSample = true)
    }

    fun withActiveIngredient(
        current: PhysiologyState,
        ingredient: BiochemicalIngredient,
    ): PhysiologyState {
        val updated = current.copy(activeIngredientId = ingredient.id)
        return stepSimulation(updated, dtSimSeconds = 0.0, forceSample = false)
    }

    fun getIngredientValue(state: PhysiologyState, ingredient: BiochemicalIngredient): Double {
        val core = ingredient.coreParam
        if (core != null) {
            return when (core) {
                ManipulableParam.GLUCOSE -> state.glucoseMgDl
                ManipulableParam.INSULIN -> state.insulinUiMl
                ManipulableParam.SODIUM -> state.sodiumMeqL
                ManipulableParam.POTASSIUM -> state.potassiumMeqL
                ManipulableParam.HYDRATION -> state.hydrationPct
                ManipulableParam.SYMPATHETIC -> state.sympatheticDrivePct
                ManipulableParam.OXYGEN_INFLOW -> state.fiO2Pct
            }
        }
        return state.ingredientLevels[ingredient.id] ?: ingredient.baselineVal
    }

    private fun normalizedIngredientDeviation(ing: BiochemicalIngredient, level: Double): Double {
        val highSpan = (ing.maxVal - ing.baselineVal).coerceAtLeast(1e-3)
        val lowSpan = (ing.baselineVal - ing.minVal).coerceAtLeast(1e-3)
        return if (level >= ing.baselineVal) {
            ((level - ing.baselineVal) / highSpan).coerceIn(0.0, 1.0)
        } else {
            -((ing.baselineVal - level) / lowSpan).coerceIn(0.0, 1.0)
        }
    }

    fun applyIngredientValue(
        current: PhysiologyState,
        ingredient: BiochemicalIngredient,
        newTargetValue: Double,
    ): PhysiologyState {
        val core = ingredient.coreParam
        if (core != null) {
            return applyImmediateParamDelta(
                current.copy(activeIngredientId = ingredient.id),
                core,
                newTargetValue,
            )
        }
        val clamped = newTargetValue.coerceIn(ingredient.minVal, ingredient.maxVal)
        val prevLevel = current.ingredientLevels[ingredient.id] ?: ingredient.baselineVal
        val deltaNorm = normalizedIngredientDeviation(ingredient, clamped) -
            normalizedIngredientDeviation(ingredient, prevLevel)
        val eff = ingredient.effects

        // Apply immediate pharmacodynamic onset response so vitals and 3D organs react on slider move
        val nextHco3 = (current.hco3MeqL + eff.hco3Delta * deltaNorm * 0.65).coerceIn(8.0, 36.0)
        val nextPh = (6.1 + log10(max(1.0, nextHco3) / (0.03 * max(10.0, current.paCo2MmHg)))).coerceIn(6.85, 7.68)
        val nextPaO2 = (current.paO2MmHg + eff.fiO2Delta * deltaNorm * 4.5).coerceIn(32.0, 350.0)
        val nextSpO2 = hillSpO2(nextPaO2)
        val nextMap = (current.mapMmHg + (eff.sympatheticDelta * 0.42 + eff.hydrationDelta * 1.1) * deltaNorm)
            .coerceIn(45.0, 165.0)
        val nextHr = (current.heartRateBpm + eff.sympatheticDelta * 0.48 * deltaNorm).coerceIn(34.0, 195.0)
        val nextEgfr = (current.egfrMlMin + eff.egfrDelta * deltaNorm * 0.65).coerceIn(5.0, 185.0)
        val nextGlucose = (current.glucoseMgDl + eff.glucoseDelta * deltaNorm * 0.75).coerceIn(30.0, 600.0)
        val nextInsulin = (current.insulinUiMl + eff.insulinDelta * deltaNorm * 0.75).coerceIn(1.0, 180.0)
        val nextSodium = (current.sodiumMeqL + eff.sodiumDelta * deltaNorm * 0.65).coerceIn(110.0, 170.0)
        val nextPotassium = (current.potassiumMeqL + eff.potassiumDelta * deltaNorm * 0.65).coerceIn(2.0, 8.2)

        val nextLevels = if (abs(clamped - ingredient.baselineVal) < 1e-6) {
            current.ingredientLevels - ingredient.id
        } else {
            current.ingredientLevels + (ingredient.id to clamped)
        }
        return stepSimulation(
            state = current.copy(
                activeIngredientId = ingredient.id,
                ingredientLevels = nextLevels,
                glucoseMgDl = nextGlucose,
                insulinUiMl = nextInsulin,
                sodiumMeqL = nextSodium,
                potassiumMeqL = nextPotassium,
                heartRateBpm = nextHr,
                mapMmHg = nextMap,
                egfrMlMin = nextEgfr,
                hco3MeqL = nextHco3,
                bloodPh = nextPh,
                paO2MmHg = nextPaO2,
                spO2Pct = nextSpO2,
            ),
            dtSimSeconds = 0.05,
            forceSample = true,
        )
    }

    fun stepSimulation(
        state: PhysiologyState,
        dtSimSeconds: Double,
        forceSample: Boolean = false,
    ): PhysiologyState {
        val dtMin = (dtSimSeconds / 60.0).coerceIn(0.0, 5.0)
        val derived = state.profile.derivedStats()
        val weightFactor = (72.0 / state.profile.weightKg.coerceIn(25.0, 250.0))
        val renalFactor = (state.egfrMlMin / 100.0).coerceIn(0.1, 1.6)

        // Coupled aggregate perturbations + real pharmacokinetic clearance for non-core ingredients
        var ingGlucoseBias = 0.0
        var ingInsulinBias = 0.0
        var ingSodiumBias = 0.0
        var ingPotassiumBias = 0.0
        var ingHydrationBias = 0.0
        var ingSympBias = 0.0
        var ingFiO2Bias = 0.0
        var ingHco3Bias = 0.0
        var ingEgfrBias = 0.0
        val nextIngredientLevels = LinkedHashMap<String, Double>(state.ingredientLevels.size)
        val nextIngredientRates = LinkedHashMap<String, Double>(state.ingredientLevels.size)

        if (state.ingredientLevels.isNotEmpty()) {
            state.ingredientLevels.forEach { (id, level) ->
                val ing = IngredientCatalog.byId(id)
                if (ing.coreParam == null) {
                    val k = (ln(2.0) / ing.halfLifeMin) * (0.65 + 0.35 * renalFactor)
                    val dIngPerMin = if (level >= ing.baselineVal) {
                        -(level - ing.baselineVal) * k
                    } else {
                        (ing.baselineVal - level) * (k * 0.35)
                    }
                    val nextLevel = (level + dIngPerMin * dtMin).coerceIn(ing.minVal, ing.maxVal)
                    if (abs(nextLevel - ing.baselineVal) > 1e-4) {
                        nextIngredientLevels[id] = nextLevel
                        nextIngredientRates[id] = dIngPerMin
                    }
                    val norm = normalizedIngredientDeviation(ing, nextLevel)
                    val e = ing.effects
                    ingGlucoseBias += e.glucoseDelta * norm
                    ingInsulinBias += e.insulinDelta * norm
                    ingSodiumBias += e.sodiumDelta * norm
                    ingPotassiumBias += e.potassiumDelta * norm
                    ingHydrationBias += e.hydrationDelta * norm
                    ingSympBias += e.sympatheticDelta * norm
                    ingFiO2Bias += e.fiO2Delta * norm
                    ingHco3Bias += e.hco3Delta * norm
                    ingEgfrBias += e.egfrDelta * norm
                }
            }
        }
        val effectiveHydration = (state.hydrationPct + ingHydrationBias).coerceIn(65.0, 135.0)
        val effectiveSymp = (state.sympatheticDrivePct + ingSympBias).coerceIn(35.0, 235.0)
        val effectiveFiO2 = (state.fiO2Pct + ingFiO2Bias).coerceIn(11.0, 65.0)

        // 1. Insulin kinetics (pancreatic beta-cell response + clearance)
        val targetEndogenousInsulin = 8.0 + max(0.0, state.glucoseMgDl - 90.0) * 0.28 +
            state.levers.insulinOffsetUiMl * 0.45 + ingInsulinBias
        val insulinClearanceRate = 0.095 * (0.65 + 0.35 * renalFactor) // min^-1 (~7.3 min t1/2)
        val dInsulinPerMin = (targetEndogenousInsulin - state.insulinUiMl) * insulinClearanceRate
        val nextInsulin = (state.insulinUiMl + dInsulinPerMin * dtMin).coerceIn(1.0, 180.0)

        // 2. Glucose kinetics (hepatic output vs insulin-mediated uptake + renal glucosuria > 180)
        val insulinSensitivity = (0.0125 * weightFactor) * (1.0 / (1.0 + max(0.0, derived.bmi - 25.0) * 0.04))
        val hepaticRelease = 1.65 + (effectiveSymp - 100.0) * 0.025 + ingGlucoseBias * 0.05
        val cellularUptake = insulinSensitivity * nextInsulin * (state.glucoseMgDl / 90.0)
        val renalSpillover = if (state.glucoseMgDl > 180.0) {
            (state.glucoseMgDl - 180.0) * 0.035 * renalFactor
        } else 0.0
        val dGlucosePerMin = hepaticRelease - cellularUptake - renalSpillover +
            (state.levers.glucoseOffsetMgDl * 0.002)
        val nextGlucose = (state.glucoseMgDl + dGlucosePerMin * dtMin).coerceIn(30.0, 600.0)

        // 3. Acid-Base & Respiratory Coupling
        val ketoacidLoad = max(0.0, nextGlucose - 240.0) * 0.018 * (12.0 / max(4.0, nextInsulin))
        val lactateLoad = max(0.0, 92.0 - state.spO2Pct) * 0.22 +
            max(0.0, 65.0 - state.mapMmHg) * 0.14
        val targetHco3 = (24.0 - ketoacidLoad - lactateLoad + ingHco3Bias).coerceIn(8.0, 34.0)
        val nextHco3 = state.hco3MeqL + (targetHco3 - state.hco3MeqL) * min(1.0, 0.18 * dtMin)

        // Respiratory compensation + sympathetic drive
        val metabolicAcidDrive = max(0.0, 24.0 - nextHco3) * 1.1
        val hypoxiaDrive = max(0.0, 94.0 - state.spO2Pct) * 0.75
        val targetRespRate = (14.0 + metabolicAcidDrive + hypoxiaDrive + (effectiveSymp - 100.0) * 0.05)
            .coerceIn(7.0, 38.0)
        val nextRespRate = state.respiratoryRateBpm + (targetRespRate - state.respiratoryRateBpm) * min(1.0, 0.55 * dtMin)

        val targetPaCo2 = (40.0 * (14.0 / nextRespRate)).coerceIn(18.0, 72.0)
        val nextPaCo2 = state.paCo2MmHg + (targetPaCo2 - state.paCo2MmHg) * min(1.0, 0.45 * dtMin)
        val nextPh = (6.1 + log10(max(1.0, nextHco3) / (0.03 * max(10.0, nextPaCo2)))).coerceIn(6.85, 7.68)
        val dPhPerMin = if (dtMin > 0) (nextPh - state.bloodPh) / dtMin else 0.0

        // Alveolar gas equation approximation
        val alveolarO2 = (effectiveFiO2 / 100.0) * 713.0 - (nextPaCo2 / 0.8)
        val targetPaO2 = (alveolarO2 - 8.0).coerceIn(32.0, 350.0)
        val nextPaO2 = state.paO2MmHg + (targetPaO2 - state.paO2MmHg) * min(1.0, 0.9 * dtMin)
        val nextSpO2 = hillSpO2(nextPaO2)
        val dSpO2PerMin = if (dtMin > 0) (nextSpO2 - state.spO2Pct) / dtMin else 0.0

        // 4. Electrolytes (Sodium & Potassium)
        val osmoticWaterPull = max(0.0, nextGlucose - 100.0) * 0.016
        val tbwDilutionTarget = 140.0 * (100.0 / effectiveHydration.coerceIn(70.0, 130.0)) - osmoticWaterPull + ingSodiumBias
        val dSodiumPerMin = (tbwDilutionTarget - state.sodiumMeqL) * 0.08 * (0.5 + 0.5 * renalFactor)
        val nextSodium = (state.sodiumMeqL + dSodiumPerMin * dtMin).coerceIn(110.0, 170.0)

        // Potassium: insulin drives intracellular uptake; acidosis drives extracellular K+ shift; kidneys clear excess
        val phShiftK = (7.40 - nextPh) * 5.2
        val insulinShiftK = -(nextInsulin - 8.0) * 0.016
        val renalClearanceK = (state.potassiumMeqL - 4.1) * 0.09 * renalFactor
        val targetPotassium = (4.1 + phShiftK + insulinShiftK + state.levers.potassiumOffsetMeqL * 0.35 + ingPotassiumBias)
            .coerceIn(2.0, 8.2)
        val dPotassiumPerMin = (targetPotassium - state.potassiumMeqL) * 0.14 - renalClearanceK * 0.35
        val nextPotassium = (state.potassiumMeqL + dPotassiumPerMin * dtMin).coerceIn(2.0, 8.2)

        // 5. Hemodynamics & Renal Autoregulation
        val osmolality = computeOsmolality(nextSodium, nextGlucose)
        val preloadFactor = (effectiveHydration / 100.0) * (1.0 + (nextSodium - 140.0) * 0.004)
        val svrFactor = (effectiveSymp / 100.0) * (1.0 + max(0.0, 92.0 - nextSpO2) * 0.012)
        val targetMap = (derived.restingMapMmHg * preloadFactor * (0.45 + 0.55 * svrFactor)).coerceIn(45.0, 165.0)
        val dMapPerMin = (targetMap - state.mapMmHg) * 0.75
        val nextMap = (state.mapMmHg + dMapPerMin * dtMin).coerceIn(45.0, 165.0)

        // Baroreceptor reflex: low MAP raises HR; high sympathetic raises HR; severe hyperkalemia (>6.0) causes conduction bradycardia risk
        val baroHrDelta = (derived.restingMapMmHg - nextMap) * 0.55
        val sympHrDelta = (effectiveSymp - 100.0) * 0.42
        val hyperKBrake = if (nextPotassium > 6.2) -(nextPotassium - 6.2) * 18.0 else 0.0
        val targetHr = (state.profile.restingHeartRateBpm + baroHrDelta + sympHrDelta + hyperKBrake)
            .coerceIn(34.0, 195.0)
        val dHrPerMin = (targetHr - state.heartRateBpm) * 1.15
        val nextHr = (state.heartRateBpm + dHrPerMin * dtMin).coerceIn(34.0, 195.0)

        val pulsePressure = (42.0 * preloadFactor * (0.7 + 0.3 * svrFactor)).coerceIn(20.0, 88.0)
        val nextDiastolic = (nextMap - pulsePressure / 3.0).coerceIn(35.0, 130.0)
        val nextSystolic = (nextDiastolic + pulsePressure).coerceIn(55.0, 235.0)
        val effectiveSv = derived.restingStrokeVolumeMl * preloadFactor * (1.0 - max(0.0, nextHr - 135.0) * 0.004)
        val nextCo = ((effectiveSv * nextHr) / 1000.0).coerceIn(1.8, 14.5)

        // Renal autoregulation curve for eGFR
        val perfusionFactor = when {
            nextMap < 70.0 -> (nextMap / 70.0).pow(1.7)
            nextMap > 140.0 -> 1.0 - (nextMap - 140.0) * 0.004
            else -> 0.94 + (nextMap - 70.0) / 70.0 * 0.08
        }.coerceIn(0.18, 1.15)
        val targetEgfr = (derived.baselineEgfr * perfusionFactor + ingEgfrBias).coerceIn(5.0, 185.0)
        val dEgfrPerMin = (targetEgfr - state.egfrMlMin) * 0.35
        val nextEgfr = (state.egfrMlMin + dEgfrPerMin * dtMin).coerceIn(5.0, 185.0)

        val newElapsed = state.simElapsedSeconds + dtSimSeconds
        val shouldSample = forceSample ||
            state.history.isEmpty() ||
            (newElapsed - state.history.last().elapsedSec) >= max(2.0, state.timeSpeedMultiplier * 0.5)

        val nextState = state.copy(
            simElapsedSeconds = newElapsed,
            glucoseMgDl = nextGlucose,
            insulinUiMl = nextInsulin,
            sodiumMeqL = nextSodium,
            potassiumMeqL = nextPotassium,
            heartRateBpm = nextHr,
            systolicBp = nextSystolic,
            diastolicBp = nextDiastolic,
            mapMmHg = nextMap,
            cardiacOutputLMin = nextCo,
            vascularResistanceFactor = svrFactor,
            egfrMlMin = nextEgfr,
            respiratoryRateBpm = nextRespRate,
            paO2MmHg = nextPaO2,
            spO2Pct = nextSpO2,
            paCo2MmHg = nextPaCo2,
            hco3MeqL = nextHco3,
            bloodPh = nextPh,
            serumOsmolalityMOsm = osmolality,
            ingredientLevels = nextIngredientLevels,
            ingredientRatesPerMin = nextIngredientRates,
        )

        return enrichReadoutsAndChain(
            state = nextState,
            dGlucosePerMin = dGlucosePerMin,
            dInsulinPerMin = dInsulinPerMin,
            dSodiumPerMin = dSodiumPerMin,
            dPotassiumPerMin = dPotassiumPerMin,
            dHrPerMin = dHrPerMin,
            dMapPerMin = dMapPerMin,
            dEgfrPerMin = dEgfrPerMin,
            dSpO2PerMin = dSpO2PerMin,
            dPhPerMin = dPhPerMin,
            appendSample = shouldSample,
        )
    }

    private fun enrichReadoutsAndChain(
        state: PhysiologyState,
        dGlucosePerMin: Double,
        dInsulinPerMin: Double,
        dSodiumPerMin: Double,
        dPotassiumPerMin: Double,
        dHrPerMin: Double,
        dMapPerMin: Double,
        dEgfrPerMin: Double,
        dSpO2PerMin: Double,
        dPhPerMin: Double,
        appendSample: Boolean,
    ): PhysiologyState {
        val glucoseReadout = buildReadout(
            id = "glucose", label = "Blood Glucose", value = state.glucoseMgDl, unit = "mg/dL",
            normalRange = "70–99", ratePerMin = dGlucosePerMin, halfLifeMin = 42.0,
            lowWarn = 70.0, lowCrit = 54.0, highWarn = 140.0, highCrit = 240.0,
        )
        val insulinReadout = buildReadout(
            id = "insulin", label = "Serum Insulin", value = state.insulinUiMl, unit = "μIU/mL",
            normalRange = "2.6–24.9", ratePerMin = dInsulinPerMin, halfLifeMin = 7.3,
            lowWarn = 2.5, lowCrit = 1.5, highWarn = 30.0, highCrit = 75.0,
        )
        val sodiumReadout = buildReadout(
            id = "sodium", label = "Sodium (Na⁺)", value = state.sodiumMeqL, unit = "mEq/L",
            normalRange = "135–145", ratePerMin = dSodiumPerMin, halfLifeMin = 180.0,
            lowWarn = 135.0, lowCrit = 125.0, highWarn = 145.0, highCrit = 155.0,
        )
        val potassiumReadout = buildReadout(
            id = "potassium", label = "Potassium (K⁺)", value = state.potassiumMeqL, unit = "mEq/L",
            normalRange = "3.5–5.0", ratePerMin = dPotassiumPerMin, halfLifeMin = 38.0,
            lowWarn = 3.5, lowCrit = 2.8, highWarn = 5.1, highCrit = 5.8,
        )
        val hrReadout = buildReadout(
            id = "hr", label = "Heart Rate", value = state.heartRateBpm, unit = "bpm",
            normalRange = "60–100", ratePerMin = dHrPerMin, halfLifeMin = 1.2,
            lowWarn = 58.0, lowCrit = 45.0, highWarn = 100.0, highCrit = 135.0,
        )
        val mapReadout = buildReadout(
            id = "map", label = "Mean Arterial BP", value = state.mapMmHg, unit = "mmHg",
            normalRange = "70–100", ratePerMin = dMapPerMin, halfLifeMin = 1.8,
            lowWarn = 70.0, lowCrit = 60.0, highWarn = 105.0, highCrit = 125.0,
        )
        val egfrReadout = buildReadout(
            id = "egfr", label = "Renal eGFR", value = state.egfrMlMin, unit = "mL/min",
            normalRange = "≥90", ratePerMin = dEgfrPerMin, halfLifeMin = 12.0,
            lowWarn = 60.0, lowCrit = 30.0, highWarn = 150.0, highCrit = 180.0,
        )
        val spo2Readout = buildReadout(
            id = "spo2", label = "O₂ Saturation", value = state.spO2Pct, unit = "%",
            normalRange = "95–100", ratePerMin = dSpO2PerMin, halfLifeMin = 1.1,
            lowWarn = 94.0, lowCrit = 88.0, highWarn = 101.0, highCrit = 105.0,
        )
        val phReadout = buildReadout(
            id = "ph", label = "Arterial pH", value = state.bloodPh, unit = "pH",
            normalRange = "7.35–7.45", ratePerMin = dPhPerMin, halfLifeMin = 4.5,
            lowWarn = 7.35, lowCrit = 7.20, highWarn = 7.45, highCrit = 7.55,
        )

        val metrics = linkedMapOf(
            "glucose" to glucoseReadout,
            "insulin" to insulinReadout,
            "sodium" to sodiumReadout,
            "potassium" to potassiumReadout,
            "hr" to hrReadout,
            "map" to mapReadout,
            "egfr" to egfrReadout,
            "spo2" to spo2Readout,
            "ph" to phReadout,
        )

        // Add live MetricReadout for every active or modified non-core ingredient
        val trackedNonCoreIds = LinkedHashSet<String>()
        state.ingredientLevels.keys.forEach { trackedNonCoreIds.add(it) }
        val activeIng = IngredientCatalog.byId(state.activeIngredientId)
        if (activeIng.coreParam == null) {
            trackedNonCoreIds.add(activeIng.id)
        }
        trackedNonCoreIds.forEach { ingId ->
            val ing = IngredientCatalog.byId(ingId)
            if (ing.coreParam == null) {
                val lvl = state.ingredientLevels[ingId] ?: ing.baselineVal
                val rate = state.ingredientRatesPerMin[ingId] ?: 0.0
                val readout = buildReadout(
                    id = "ing_$ingId",
                    label = ing.name,
                    value = lvl,
                    unit = ing.unit,
                    normalRange = ing.normalRange,
                    ratePerMin = rate,
                    halfLifeMin = ing.halfLifeMin,
                    lowWarn = ing.lowWarn,
                    lowCrit = ing.lowCrit,
                    highWarn = ing.highWarn,
                    highCrit = ing.highCrit,
                )
                metrics["ing_$ingId"] = readout
            }
        }

        val regionMap = buildRegionTelemetry(state, metrics)
        val chain = buildCausalChain(state, metrics)
        val criticalCount = metrics.values.count { it.severity == SeverityLevel.CRITICAL }

        val updatedHistory = if (appendSample) {
            val sample = PhysiologySample(
                elapsedSec = state.simElapsedSeconds,
                glucoseMgDl = state.glucoseMgDl,
                potassiumMeqL = state.potassiumMeqL,
                sodiumMeqL = state.sodiumMeqL,
                heartRateBpm = state.heartRateBpm,
                mapMmHg = state.mapMmHg,
                egfrMlMin = state.egfrMlMin,
                spO2Pct = state.spO2Pct,
            )
            (state.history + sample).takeLast(90)
        } else {
            state.history
        }

        val stimulusSummary = buildStimulusSummary(state)

        return state.copy(
            metrics = metrics,
            regionTelemetry = regionMap,
            causalChain = chain,
            history = updatedHistory,
            stimulusSummary = stimulusSummary,
            criticalCount = criticalCount,
        )
    }

    private fun buildReadout(
        id: String,
        label: String,
        value: Double,
        unit: String,
        normalRange: String,
        ratePerMin: Double,
        halfLifeMin: Double?,
        lowWarn: Double,
        lowCrit: Double,
        highWarn: Double,
        highCrit: Double,
    ): MetricReadout {
        val severity = when {
            value <= lowCrit || value >= highCrit -> SeverityLevel.CRITICAL
            value < lowWarn || value > highWarn -> SeverityLevel.CAUTION
            else -> SeverityLevel.NORMAL
        }
        val etaSec = when {
            ratePerMin > 0.02 && value < highCrit -> ((highCrit - value) / ratePerMin) * 60.0
            ratePerMin < -0.02 && value > lowCrit -> ((value - lowCrit) / abs(ratePerMin)) * 60.0
            else -> null
        }?.coerceAtMost(86400.0)
        val thresholdLabel = when {
            value >= highCrit -> "≥${formatCompact(highCrit)} $unit CRIT"
            value <= lowCrit -> "≤${formatCompact(lowCrit)} $unit CRIT"
            ratePerMin > 0.02 -> "→ ${formatCompact(highCrit)} $unit"
            ratePerMin < -0.02 -> "→ ${formatCompact(lowCrit)} $unit"
            else -> "Equilibrium"
        }
        return MetricReadout(
            id = id,
            label = label,
            value = value,
            unit = unit,
            normalRange = normalRange,
            ratePerMin = ratePerMin,
            halfLifeMin = halfLifeMin,
            etaToCriticalSec = etaSec,
            thresholdLabel = thresholdLabel,
            severity = severity,
        )
    }

    private fun buildRegionTelemetry(
        state: PhysiologyState,
        metrics: Map<String, MetricReadout>,
    ): Map<BodyRegion, RegionTelemetry> {
        val brainSeverity = maxSeverity(
            if (state.sodiumMeqL < 126 || state.sodiumMeqL > 154) SeverityLevel.CRITICAL
            else if (state.sodiumMeqL < 134 || state.sodiumMeqL > 146) SeverityLevel.CAUTION
            else SeverityLevel.NORMAL,
            if (state.glucoseMgDl < 55 || state.spO2Pct < 88) SeverityLevel.CRITICAL
            else if (state.glucoseMgDl < 70 || state.spO2Pct < 94) SeverityLevel.CAUTION
            else SeverityLevel.NORMAL,
        )
        val brainSymptoms = buildList {
            if (state.glucoseMgDl < 65) add("Neuroglycopenia (<65 mg/dL): confusion, tremor")
            if (state.sodiumMeqL < 132) add("Cerebral osmotic edema risk (Na⁺ ${formatCompact(state.sodiumMeqL)})")
            if (state.sodiumMeqL > 148) add("Hyperosmolar neuronal shrinkage (${formatCompact(state.serumOsmolalityMOsm)} mOsm)")
            if (state.spO2Pct < 92) add("Cerebral hypoxia (SpO₂ ${formatCompact(state.spO2Pct)}%)")
            if (isEmpty()) add("Intact cerebral perfusion (MAP ${state.mapMmHg.toInt()} mmHg)")
        }

        val heartSeverity = maxSeverity(
            metrics.getValue("hr").severity,
            metrics.getValue("potassium").severity,
        )
        val heartSymptoms = buildList {
            if (state.potassiumMeqL >= 5.6) add("Peaked T-waves & QRS widening (K⁺ ${formatCompact(state.potassiumMeqL)})")
            if (state.potassiumMeqL <= 3.1) add("U-wave & ventricular ectopy risk (K⁺ ${formatCompact(state.potassiumMeqL)})")
            if (state.heartRateBpm >= 110) add("Sinus tachycardia (${state.heartRateBpm.toInt()} bpm, CO ${formatCompact(state.cardiacOutputLMin)} L/m)")
            if (state.heartRateBpm <= 50) add("Bradycardic conduction delay (${state.heartRateBpm.toInt()} bpm)")
            if (isEmpty()) add("Normal sinus rhythm (${state.heartRateBpm.toInt()} bpm)")
        }

        val lungsSeverity = maxSeverity(
            metrics.getValue("spo2").severity,
            metrics.getValue("ph").severity,
        )
        val lungsSymptoms = buildList {
            if (state.spO2Pct < 92) add("Hypoxemia (PaO₂ ${state.paO2MmHg.toInt()} mmHg, SpO₂ ${formatCompact(state.spO2Pct)}%)")
            if (state.respiratoryRateBpm >= 22) add("Compensatory tachypnea (${state.respiratoryRateBpm.toInt()} br/m, PaCO₂ ${state.paCo2MmHg.toInt()})")
            if (state.bloodPh < 7.34) add("Acidemia pH ${String.format("%.2f", state.bloodPh)} triggering respiratory blow-off")
            if (isEmpty()) add("Balanced gas exchange (${state.respiratoryRateBpm.toInt()} br/m)")
        }

        val liverSeverity = maxSeverity(
            metrics.getValue("glucose").severity,
            metrics.getValue("insulin").severity,
        )
        val liverSymptoms = buildList {
            if (state.glucoseMgDl > 180) add("Glycogen saturation + osmotic spillover >180 mg/dL")
            if (state.glucoseMgDl < 70) add("Hepatic glycogenolysis activated (G ${state.glucoseMgDl.toInt()} mg/dL)")
            if (state.insulinUiMl > 35) add("Hyperinsulinemic K⁺ intracellular shift (${formatCompact(state.insulinUiMl)} μIU/mL)")
            if (isEmpty()) add("Hepatic glucose flux balanced (${formatCompact(metrics.getValue("glucose").ratePerMin)} /min)")
        }

        val kidneySeverity = maxSeverity(
            metrics.getValue("egfr").severity,
            metrics.getValue("sodium").severity,
        )
        val kidneySymptoms = buildList {
            if (state.egfrMlMin < 60) add("Reduced filtration (eGFR ${formatCompact(state.egfrMlMin)} mL/min)")
            if (state.glucoseMgDl > 180) add("Osmotic diuresis above renal threshold (180 mg/dL)")
            if (state.mapMmHg < 70) add("Prerenal hypoperfusion (MAP ${state.mapMmHg.toInt()} < 70 mmHg)")
            if (isEmpty()) add("Normal nephron clearance (eGFR ${formatCompact(state.egfrMlMin)} mL/min)")
        }

        val vesselsSeverity = metrics.getValue("map").severity
        val vesselsSymptoms = buildList {
            if (state.systolicBp >= 140) add("Arteriolar vasoconstriction (${state.systolicBp.toInt()}/${state.diastolicBp.toInt()} mmHg)")
            if (state.systolicBp <= 90) add("Hypovolemic/vasodilatory hypotension (MAP ${state.mapMmHg.toInt()})")
            if (state.hydrationPct < 88) add("Hemoconcentration (Vol ${state.hydrationPct.toInt()}%, ${state.serumOsmolalityMOsm.toInt()} mOsm)")
            if (isEmpty()) add("Laminar vascular tone (SVR ${String.format("%.2f", state.vascularResistanceFactor)}x)")
        }

        val splanchnicPerfusionPct = ((state.mapMmHg / 90.0) / state.vascularResistanceFactor.coerceAtLeast(0.6) * 100.0)
            .coerceIn(28.0, 145.0)
        val digestiveSeverity = when {
            splanchnicPerfusionPct < 55.0 || state.potassiumMeqL > 6.2 || state.glucoseMgDl > 260.0 -> SeverityLevel.CRITICAL
            splanchnicPerfusionPct < 75.0 || state.sympatheticDrivePct > 145.0 || state.hydrationPct < 88.0 -> SeverityLevel.CAUTION
            else -> SeverityLevel.NORMAL
        }
        val digestiveSymptoms = buildList {
            if (splanchnicPerfusionPct < 75.0) add("Mesenteric shunting (perfusion ${splanchnicPerfusionPct.toInt()}%)")
            if (state.sympatheticDrivePct > 140.0) add("Sympathetic ileus / slowed gastric emptying")
            if (state.glucoseMgDl > 200.0) add("Delayed duodenal motility (G ${state.glucoseMgDl.toInt()} mg/dL)")
            if (isEmpty()) add("Splanchnic absorption active (${splanchnicPerfusionPct.toInt()}% flow)")
        }

        val baseMap = mapOf(
            BodyRegion.BRAIN to RegionTelemetry(
                BodyRegion.BRAIN, brainSeverity,
                "${state.serumOsmolalityMOsm.toInt()} mOsm",
                "SpO₂ ${formatCompact(state.spO2Pct)}%",
                "pH ${String.format("%.2f", state.bloodPh)}",
                metrics.getValue("spo2").etaToCriticalSec?.let { "ETA ${formatDuration(it)}" } ?: "Stable",
                brainSymptoms,
            ),
            BodyRegion.HEART to RegionTelemetry(
                BodyRegion.HEART, heartSeverity,
                "${state.heartRateBpm.toInt()} bpm",
                "K⁺ ${String.format("%.2f", state.potassiumMeqL)} mEq/L",
                "CO ${String.format("%.1f", state.cardiacOutputLMin)} L/m",
                metrics.getValue("potassium").etaToCriticalSec?.let { "K⁺ ETA ${formatDuration(it)}" } ?: "Steady",
                heartSymptoms,
            ),
            BodyRegion.LUNGS to RegionTelemetry(
                BodyRegion.LUNGS, lungsSeverity,
                "${formatCompact(state.spO2Pct)}% SpO₂",
                "RR ${state.respiratoryRateBpm.toInt()} /min",
                "PaCO₂ ${state.paCo2MmHg.toInt()} mmHg",
                metrics.getValue("ph").etaToCriticalSec?.let { "pH ETA ${formatDuration(it)}" } ?: "Steady",
                lungsSymptoms,
            ),
            BodyRegion.LIVER_PANCREAS to RegionTelemetry(
                BodyRegion.LIVER_PANCREAS, liverSeverity,
                "${state.glucoseMgDl.toInt()} mg/dL",
                "Ins ${formatCompact(state.insulinUiMl)} μIU",
                "dG/dt ${formatSigned(metrics.getValue("glucose").ratePerMin)}/m",
                metrics.getValue("glucose").etaToCriticalSec?.let { "ETA ${formatDuration(it)}" } ?: "Balanced",
                liverSymptoms,
            ),
            BodyRegion.DIGESTIVE_GI to RegionTelemetry(
                BodyRegion.DIGESTIVE_GI, digestiveSeverity,
                "Flow ${splanchnicPerfusionPct.toInt()}%",
                "Osm ${state.serumOsmolalityMOsm.toInt()} mOsm",
                "Symp ${state.sympatheticDrivePct.toInt()}%",
                if (digestiveSeverity == SeverityLevel.NORMAL) "Intact" else "Alert",
                digestiveSymptoms,
            ),
            BodyRegion.KIDNEYS to RegionTelemetry(
                BodyRegion.KIDNEYS, kidneySeverity,
                "${formatCompact(state.egfrMlMin)} eGFR",
                "Na⁺ ${formatCompact(state.sodiumMeqL)} mEq/L",
                "dK/dt ${formatSigned(metrics.getValue("potassium").ratePerMin)}/m",
                metrics.getValue("egfr").etaToCriticalSec?.let { "ETA ${formatDuration(it)}" } ?: "Stable",
                kidneySymptoms,
            ),
            BodyRegion.VESSELS to RegionTelemetry(
                BodyRegion.VESSELS, vesselsSeverity,
                "${state.systolicBp.toInt()}/${state.diastolicBp.toInt()}",
                "MAP ${state.mapMmHg.toInt()} mmHg",
                "Vol ${state.hydrationPct.toInt()}%",
                metrics.getValue("map").etaToCriticalSec?.let { "ETA ${formatDuration(it)}" } ?: "Steady",
                vesselsSymptoms,
            ),
        )

        // Overlay active/modified non-core ingredients directly onto their target 3D organs & containers
        val enriched = baseMap.toMutableMap()
        state.ingredientLevels.forEach { (ingId, level) ->
            val ing = IngredientCatalog.byId(ingId)
            if (ing.coreParam == null) {
                val region = ing.targetRegion
                val existing = enriched[region] ?: return@forEach
                val ingSev = ing.severityFor(level)
                val mergedSev = maxSeverity(existing.severity, ingSev)
                val rate = state.ingredientRatesPerMin[ingId] ?: 0.0
                val isFocused = state.activeIngredientId == ingId
                val updatedSymptoms = listOf(ing.clinicalStatusText(level)) + existing.symptoms
                enriched[region] = existing.copy(
                    severity = mergedSev,
                    secondaryNumber = if (isFocused || ingSev != SeverityLevel.NORMAL) {
                        "${ing.symbol} ${formatCompact(level)} ${ing.unit}"
                    } else {
                        existing.secondaryNumber
                    },
                    rateText = if (isFocused && abs(rate) > 1e-3) {
                        "d[${ing.symbol}]/dt ${formatSigned(rate)}/m"
                    } else {
                        existing.rateText
                    },
                    etaText = if (ingSev == SeverityLevel.CRITICAL) {
                        "${ing.symbol} CRIT"
                    } else if (ingSev == SeverityLevel.CAUTION) {
                        "${ing.symbol} WARN"
                    } else {
                        existing.etaText
                    },
                    symptoms = updatedSymptoms.distinct().take(4),
                )
            }
        }
        return enriched
    }

    private fun buildCausalChain(
        state: PhysiologyState,
        metrics: Map<String, MetricReadout>,
    ): List<CausalNode> {
        val nodes = mutableListOf<CausalNode>()
        val tBase = state.simElapsedSeconds

        // Step 1: Primary Metabolic / Electrolyte / Hemodynamic Driver
        if (abs(state.glucoseMgDl - 90.0) > 15.0 || abs(state.insulinUiMl - 8.0) > 4.0) {
            val gRate = metrics.getValue("glucose").ratePerMin
            nodes += CausalNode(
                id = "node_metabolic",
                stepOrder = nodes.size + 1,
                sourceRegion = BodyRegion.LIVER_PANCREAS,
                targetRegion = BodyRegion.VESSELS,
                title = if (state.glucoseMgDl > 140) "Hyperglycemic Osmotic Load" else if (state.glucoseMgDl < 70) "Hypoglycemic Sympathetic Trigger" else "Insulin-Glucose Kinetics",
                equationSummary = "dG/dt = Hepatic - S_I·I·G - Spillover",
                numericDelta = "G ${state.glucoseMgDl.toInt()} mg/dL (${formatSigned(gRate)}/min)",
                onsetTimeSec = max(0.0, tBase),
                severity = metrics.getValue("glucose").severity,
                symptoms = listOf("Insulin ${formatCompact(state.insulinUiMl)} μIU/mL", "Osmolality ${state.serumOsmolalityMOsm.toInt()} mOsm/kg"),
            )
        }

        if (abs(state.potassiumMeqL - 4.1) > 0.35 || abs(state.insulinUiMl - 8.0) > 8.0) {
            val kRate = metrics.getValue("potassium").ratePerMin
            nodes += CausalNode(
                id = "node_potassium",
                stepOrder = nodes.size + 1,
                sourceRegion = BodyRegion.LIVER_PANCREAS,
                targetRegion = BodyRegion.HEART,
                title = "Transcellular K⁺ & Cardiac Excitability",
                equationSummary = "ΔK⁺ ∝ -0.016·ΔI + 5.2·(7.40 - pH)",
                numericDelta = "K⁺ ${String.format("%.2f", state.potassiumMeqL)} mEq/L (${formatSigned(kRate)}/min)",
                onsetTimeSec = tBase + 14.0,
                severity = metrics.getValue("potassium").severity,
                symptoms = listOf("Resting membrane potential shift", if (state.potassiumMeqL > 5.5) "Peaked T-wave threshold reached" else "Myocardial repolarization modulated"),
            )
        }

        if (abs(state.hydrationPct - 100.0) > 3.0 || abs(state.sympatheticDrivePct - 100.0) > 12.0 || abs(state.sodiumMeqL - 140.0) > 2.5) {
            nodes += CausalNode(
                id = "node_hemodynamics",
                stepOrder = nodes.size + 1,
                sourceRegion = BodyRegion.VESSELS,
                targetRegion = BodyRegion.HEART,
                title = "Baroreceptor & Preload Reflex",
                equationSummary = "MAP = CO × SVR; ΔHR ∝ (MAP₀ - MAP)",
                numericDelta = "BP ${state.systolicBp.toInt()}/${state.diastolicBp.toInt()} mmHg • HR ${state.heartRateBpm.toInt()} bpm",
                onsetTimeSec = tBase + 6.0,
                severity = maxSeverity(metrics.getValue("map").severity, metrics.getValue("hr").severity),
                symptoms = listOf("CO ${String.format("%.1f", state.cardiacOutputLMin)} L/min", "SVR ${String.format("%.2f", state.vascularResistanceFactor)}x baseline"),
            )
        }

        // Renal Cascade
        nodes += CausalNode(
            id = "node_renal",
            stepOrder = nodes.size + 1,
            sourceRegion = BodyRegion.HEART,
            targetRegion = BodyRegion.KIDNEYS,
            title = "Renal Perfusion & Clearance Coupling",
            equationSummary = "eGFR = CKD_EPI × f(MAP); Cl_K ∝ eGFR",
            numericDelta = "eGFR ${formatCompact(state.egfrMlMin)} mL/min • Na⁺ ${formatCompact(state.sodiumMeqL)}",
            onsetTimeSec = tBase + 45.0,
            severity = metrics.getValue("egfr").severity,
            symptoms = listOf(
                if (state.glucoseMgDl > 180) "Glucosuria active (>180 mg/dL)" else "Tubular reabsorption intact",
                if (state.mapMmHg < 70) "Filtration pressure dropping" else "Autoregulated nephron flow",
            ),
        )

        // Respiratory & Acid-Base Cascade
        nodes += CausalNode(
            id = "node_respiratory",
            stepOrder = nodes.size + 1,
            sourceRegion = BodyRegion.KIDNEYS,
            targetRegion = BodyRegion.LUNGS,
            title = "Henderson-Hasselbalch & O₂ Transport",
            equationSummary = "pH = 6.1 + log₁₀(HCO₃⁻ / 0.03·PaCO₂)",
            numericDelta = "pH ${String.format("%.2f", state.bloodPh)} • SpO₂ ${formatCompact(state.spO2Pct)}%",
            onsetTimeSec = tBase + 22.0,
            severity = maxSeverity(metrics.getValue("ph").severity, metrics.getValue("spo2").severity),
            symptoms = listOf("RR ${state.respiratoryRateBpm.toInt()} br/min", "PaCO₂ ${state.paCo2MmHg.toInt()} mmHg"),
        )

        // Neurovascular Outcome
        nodes += CausalNode(
            id = "node_cns",
            stepOrder = nodes.size + 1,
            sourceRegion = BodyRegion.LUNGS,
            targetRegion = BodyRegion.BRAIN,
            title = "Cerebral Perfusion & Osmotic Equilibrium",
            equationSummary = "Osm = 2·Na⁺ + G/18; O₂ Delivery = CO·SpO₂",
            numericDelta = "${state.serumOsmolalityMOsm.toInt()} mOsm/kg • PaO₂ ${state.paO2MmHg.toInt()} mmHg",
            onsetTimeSec = tBase + 68.0,
            severity = state.regionTelemetry[BodyRegion.BRAIN]?.severity ?: SeverityLevel.NORMAL,
            symptoms = state.regionTelemetry[BodyRegion.BRAIN]?.symptoms ?: emptyList(),
        )

        state.ingredientLevels.entries.forEach { (id, level) ->
            val ing = IngredientCatalog.byId(id)
            if (ing.coreParam == null) {
                val rate = state.ingredientRatesPerMin[id] ?: 0.0
                val sev = ing.severityFor(level)
                nodes += CausalNode(
                    id = "node_ing_$id",
                    stepOrder = nodes.size + 1,
                    sourceRegion = BodyRegion.DIGESTIVE_GI,
                    targetRegion = ing.targetRegion,
                    title = "${ing.name} Cascade",
                    equationSummary = ing.equationFormula,
                    numericDelta = "${ing.symbol} ${formatCompact(level)} ${ing.unit} (${formatSigned(rate)}/min)",
                    onsetTimeSec = max(0.0, tBase + 2.0),
                    severity = sev,
                    symptoms = listOf(ing.clinicalStatusText(level)),
                )
            }
        }

        // Put active ingredient node first if present so user immediately sees its math & delta
        val orderedNodes = nodes.sortedBy { if (it.id == "node_ing_${state.activeIngredientId}") 0 else 1 }
        return orderedNodes.mapIndexed { idx, node -> node.copy(stepOrder = idx + 1) }
    }

    private fun buildStimulusSummary(state: PhysiologyState): String {
        val parts = mutableListOf<String>()
        if (abs(state.glucoseMgDl - 90.0) > 15.0) parts += "G ${state.glucoseMgDl.toInt()}"
        if (abs(state.insulinUiMl - 8.0) > 4.0) parts += "Ins ${state.insulinUiMl.toInt()}"
        if (abs(state.potassiumMeqL - 4.1) > 0.3) parts += "K⁺ ${String.format("%.1f", state.potassiumMeqL)}"
        if (abs(state.sodiumMeqL - 140.0) > 2.5) parts += "Na⁺ ${state.sodiumMeqL.toInt()}"
        if (abs(state.hydrationPct - 100.0) > 3.0) parts += "Vol ${state.hydrationPct.toInt()}%"
        if (abs(state.sympatheticDrivePct - 100.0) > 12.0) parts += "Symp ${state.sympatheticDrivePct.toInt()}%"
        if (abs(state.fiO2Pct - 21.0) > 1.5) parts += "FiO₂ ${state.fiO2Pct.toInt()}%"
        state.ingredientLevels.entries.take(3).forEach { (id, level) ->
            val ing = IngredientCatalog.byId(id)
            parts += "${ing.symbol} ${formatCompact(level)}"
        }
        return if (parts.isEmpty()) "Baseline Homeostasis" else parts.joinToString(" • ").take(195)
    }

    private fun hillSpO2(paO2: Double): Double {
        val p = paO2.coerceIn(10.0, 400.0)
        val n = 2.7
        val p50 = 26.8
        return (100.0 * p.pow(n) / (p.pow(n) + p50.pow(n))).coerceIn(40.0, 99.9)
    }

    private fun computeOsmolality(sodium: Double, glucose: Double): Double =
        2.0 * sodium + (glucose / 18.0) + 5.0

    private fun maxSeverity(a: SeverityLevel, b: SeverityLevel): SeverityLevel =
        if (a.ordinal >= b.ordinal) a else b

    fun formatDuration(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    fun formatCompact(v: Double): String =
        if (abs(v) >= 100.0) String.format("%.0f", v)
        else String.format("%.1f", v)

    fun formatSigned(v: Double): String =
        (if (v >= 0) "+" else "") + String.format("%.2f", v)
}
