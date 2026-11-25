package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.DashboardSkin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.bioPreferencesDataStore by preferencesDataStore(name = "biocascade_prefs")

class PreferencesRepository(private val context: Context) {

    val selectedSkin: Flow<DashboardSkin> = context.bioPreferencesDataStore.data.map { prefs ->
        val raw = prefs[KEY_SKIN]
        DashboardSkin.entries.firstOrNull { it.name == raw } ?: DashboardSkin.CLINICAL_OBSIDIAN
    }

    val hipaaSessionLockEnabled: Flow<Boolean> = context.bioPreferencesDataStore.data.map { prefs ->
        prefs[KEY_HIPAA_LOCK] ?: false
    }

    suspend fun setSelectedSkin(skin: DashboardSkin) {
        context.bioPreferencesDataStore.edit { prefs ->
            prefs[KEY_SKIN] = skin.name
        }
    }

    suspend fun setHipaaSessionLock(enabled: Boolean) {
        context.bioPreferencesDataStore.edit { prefs ->
            prefs[KEY_HIPAA_LOCK] = enabled
        }
    }

    private companion object {
        val KEY_SKIN = stringPreferencesKey("dashboard_skin")
        val KEY_HIPAA_LOCK = booleanPreferencesKey("hipaa_session_lock")
    }
}
