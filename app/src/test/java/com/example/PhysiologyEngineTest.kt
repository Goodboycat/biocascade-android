package com.example

import com.example.physiology.BiologicalSex
import com.example.physiology.HumanBaselineProfile
import com.example.physiology.IngredientCatalog
import com.example.physiology.IngredientCategory
import com.example.physiology.ManipulableParam
import com.example.physiology.PhysiologyEngine
import com.example.physiology.SeverityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysiologyEngineTest {

    @Test
    fun linkedBiometrics_computeExpectedEgfrAndBloodVolume() {
        val male = HumanBaselineProfile(
            ageYears = 32L,
            biologicalSex = BiologicalSex.MALE.name,
            weightKg = 72.0,
            heightCm = 175.0,
            serumCreatinineMgDl = 0.95,
        ).derivedStats()

        val female = HumanBaselineProfile(
            ageYears = 32L,
            biologicalSex = BiologicalSex.FEMALE.name,
            weightKg = 60.0,
            heightCm = 165.0,
            serumCreatinineMgDl = 0.95,
        ).derivedStats()

        assertTrue("Blood volume should be ~4.5-5.5L", male.totalBloodVolumeMl in 4400.0..5600.0)
        assertTrue("Female blood volume should be lower than larger male", female.totalBloodVolumeMl < male.totalBloodVolumeMl)
        assertTrue("2021 CKD-EPI eGFR for 32yo Cr 0.95 should be >80", male.baselineEgfr > 80.0)
    }

    @Test
    fun glucoseSpike_triggersInsulinResponseAndCausalChain() {
        val profile = HumanBaselineProfile()
        var state = PhysiologyEngine.initialState(profile)
        state = PhysiologyEngine.applyImmediateParamDelta(state, ManipulableParam.GLUCOSE, 320.0)
        repeat(40) {
            state = PhysiologyEngine.stepSimulation(state, dtSimSeconds = 15.0)
        }

        assertEquals(SeverityLevel.CRITICAL, state.metrics.getValue("glucose").severity)
        assertTrue("Insulin should rise in response to hyperglycemia", state.insulinUiMl > 15.0)
        assertTrue("Causal chain should contain metabolic node", state.causalChain.any { it.id == "node_metabolic" })
    }

    @Test
    fun hyperkalemia_triggersCardiacCriticalSeverityAndEta() {
        val profile = HumanBaselineProfile()
        var state = PhysiologyEngine.initialState(profile)
        state = PhysiologyEngine.applyImmediateParamDelta(state, ManipulableParam.POTASSIUM, 6.4)
        state = PhysiologyEngine.stepSimulation(state, dtSimSeconds = 10.0)

        assertEquals(SeverityLevel.CRITICAL, state.metrics.getValue("potassium").severity)
        assertTrue("Heart region should report critical severity", state.regionTelemetry.values.any { it.severity == SeverityLevel.CRITICAL })
    }

    @Test
    fun ingredientCatalog_containsOverHundredUniqueMetalsVitaminsAndSearchMatches() {
        val all = IngredientCatalog.all
        assertTrue("Catalog must contain at least 100 individual ingredients", all.size >= 100)
        assertEquals("All ingredient IDs must be unique", all.size, all.map { it.id }.toSet().size)
        assertTrue(all.any { it.category == IngredientCategory.METAL && it.id == "iron" })
        assertTrue(all.any { it.category == IngredientCategory.VITAMIN && it.id == "vitamin_d3" })
        assertTrue(IngredientCatalog.search("zinc").any { it.id == "zinc" })
        assertTrue(IngredientCatalog.search("vitamin").size >= 10)
    }

    @Test
    fun nonCoreIngredient_caffeineAndLactate_coupleToPhysiologyAndCausalChain() {
        var state = PhysiologyEngine.initialState(HumanBaselineProfile())
        val caffeine = IngredientCatalog.byId("caffeine")
        state = PhysiologyEngine.applyIngredientValue(state, caffeine, 36.0)
        repeat(20) {
            state = PhysiologyEngine.stepSimulation(state, dtSimSeconds = 12.0)
        }
        assertTrue("High caffeine should elevate heart rate (${state.heartRateBpm})", state.heartRateBpm > 71.0)
        assertTrue("High caffeine should raise MAP (${state.mapMmHg})", state.mapMmHg > 93.0)
        assertTrue("Causal chain should include active ingredient cascade", state.causalChain.any { it.id == "node_ing_caffeine" })
    }
}
