package com.example.data

import android.content.Context
import com.example.R
import com.example.physiology.BiologicalSex
import com.example.physiology.HumanBaselineProfile
import com.example.physiology.PhysiologyState
import com.example.physiology.SimulationRecord
import com.example.physiology.HipaaAuditEntry
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class ClinicalRecordsRepository(
    private val db: FirebaseFirestore?,
    private val cryptoManager: KeystoreCryptoManager,
    private val auth: FirebaseAuth? = null,
) {
    constructor(context: Context) : this(
        db = runCatching {
            FirebaseFirestore.getInstance(
                context.applicationContext.getString(R.string.firestore_database_id)
            )
        }.getOrNull(),
        cryptoManager = KeystoreCryptoManager(context.applicationContext),
        auth = runCatching { Firebase.auth }.getOrNull(),
    )

    private fun requireUserId(): String =
        auth?.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing clinical records.")

    fun observeProfiles(userId: String): Flow<List<HumanBaselineProfile>> = flow {
        val firestore = db
        if (firestore == null || auth == null) {
            emit(emptyList())
            return@flow
        }
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val path = "users/$userId/profiles"
        emitAll(
            firestore.collection("users").document(userId).collection("profiles")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        HumanBaselineProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeRecords(userId: String): Flow<List<SimulationRecord>> = flow {
        val firestore = db
        if (firestore == null || auth == null) {
            emit(emptyList())
            return@flow
        }
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val path = "users/$userId/records"
        emitAll(
            firestore.collection("users").document(userId).collection("records")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(40)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SimulationRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeAuditLogs(userId: String): Flow<List<HipaaAuditEntry>> = flow {
        val firestore = db
        if (firestore == null || auth == null) {
            emit(emptyList())
            return@flow
        }
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val path = "users/$userId/auditLogs"
        emitAll(
            firestore.collection("users").document(userId).collection("auditLogs")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(25)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        HipaaAuditEntry::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun saveProfile(userId: String, profile: HumanBaselineProfile): Result<Unit> {
        var docPath = "users/$userId/profiles"
        return try {
            val cleanLabel = profile.label.trim().ifEmpty { "Adult Baseline" }.take(80)
            val safeAge = profile.ageYears.coerceIn(1L, 120L)
            val safeSex = BiologicalSex.fromRaw(profile.biologicalSex).name
            val safeWeight = profile.weightKg.coerceIn(20.0, 300.0)
            val safeHeight = profile.heightCm.coerceIn(80.0, 250.0)
            val safeCr = profile.serumCreatinineMgDl.coerceIn(0.2, 15.0)
            val safeHr = profile.restingHeartRateBpm.coerceIn(30L, 180L)
            val safeSbp = profile.restingSystolicMmHg.coerceIn(60L, 240L)
            val safeDbp = profile.restingDiastolicMmHg.coerceIn(40L, 140L)

            val jsonPayload = JSONObject().apply {
                put("label", cleanLabel)
                put("age", safeAge)
                put("sex", safeSex)
                put("weightKg", safeWeight)
                put("heightCm", safeHeight)
                put("creatinine", safeCr)
            }.toString()
            cryptoManager.writeEncryptedCache("profile_latest.enc", jsonPayload)

            val firestore = db ?: return Result.failure(
                IllegalStateException("Saved to encrypted local device vault (cloud sync inactive).")
            )
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch" }
            val colRef = firestore.collection("users").document(uid).collection("profiles")
            val isUpdate = profile.id.isNotBlank() && Regex("^[a-zA-Z0-9_\\-]+$").matches(profile.id)
            if (isUpdate) {
                val docRef = colRef.document(profile.id)
                docPath = docRef.path
                docRef.update(
                    mapOf(
                        "label" to cleanLabel,
                        "ageYears" to safeAge,
                        "biologicalSex" to safeSex,
                        "weightKg" to safeWeight,
                        "heightCm" to safeHeight,
                        "serumCreatinineMgDl" to safeCr,
                        "restingHeartRateBpm" to safeHr,
                        "restingSystolicMmHg" to safeSbp,
                        "restingDiastolicMmHg" to safeDbp,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            } else {
                val docRef = colRef.document()
                docPath = docRef.path
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "label" to cleanLabel,
                        "ageYears" to safeAge,
                        "biologicalSex" to safeSex,
                        "weightKg" to safeWeight,
                        "heightCm" to safeHeight,
                        "serumCreatinineMgDl" to safeCr,
                        "restingHeartRateBpm" to safeHr,
                        "restingSystolicMmHg" to safeSbp,
                        "restingDiastolicMmHg" to safeDbp,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            }

            logAuditEventInternal(firestore, uid, "PROFILE_SAVED", "Encrypted baseline profile saved ($cleanLabel)")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveSimulationSnapshot(userId: String, state: PhysiologyState): Result<Unit> {
        var docPath = "users/$userId/records"
        return try {
            val cleanLabel = state.profile.label.trim().ifEmpty { "Adult Baseline" }.take(80)
            val cleanStimulus = state.stimulusSummary.trim().ifEmpty { "Baseline" }.take(200)

            val vaultJson = JSONObject().apply {
                put("profile", cleanLabel)
                put("stimulus", cleanStimulus)
                put("elapsedSec", state.simElapsedSeconds)
                put("glucose", state.glucoseMgDl)
                put("insulin", state.insulinUiMl)
                put("sodium", state.sodiumMeqL)
                put("potassium", state.potassiumMeqL)
                put("hr", state.heartRateBpm)
                put("bp", "${state.systolicBp.toInt()}/${state.diastolicBp.toInt()}")
                put("egfr", state.egfrMlMin)
                put("spo2", state.spO2Pct)
                put("ph", state.bloodPh)
            }.toString()
            val digest = cryptoManager.writeEncryptedCache("snapshot_latest.enc", vaultJson)

            val firestore = db ?: return Result.failure(
                IllegalStateException("Saved to encrypted local device vault (cloud sync inactive).")
            )
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch" }

            val docRef = firestore.collection("users").document(uid).collection("records").document()
            docPath = docRef.path
            docRef.set(
                mapOf(
                    "userId" to uid,
                    "profileLabel" to cleanLabel,
                    "stimulusSummary" to cleanStimulus,
                    "simElapsedSeconds" to state.simElapsedSeconds.coerceAtLeast(0.0),
                    "glucoseMgDl" to state.glucoseMgDl.coerceIn(10.0, 1500.0),
                    "insulinUiMl" to state.insulinUiMl.coerceIn(0.0, 500.0),
                    "sodiumMeqL" to state.sodiumMeqL.coerceIn(90.0, 190.0),
                    "potassiumMeqL" to state.potassiumMeqL.coerceIn(1.5, 10.0),
                    "heartRateBpm" to state.heartRateBpm.toLong().coerceIn(20L, 260L),
                    "systolicBp" to state.systolicBp.toLong().coerceIn(40L, 280L),
                    "diastolicBp" to state.diastolicBp.toLong().coerceIn(20L, 180L),
                    "egfrMlMin" to state.egfrMlMin.coerceIn(1.0, 220.0),
                    "spO2Pct" to state.spO2Pct.coerceIn(40.0, 100.0),
                    "bloodPh" to state.bloodPh.coerceIn(6.5, 7.8),
                    "criticalCount" to state.criticalCount.toLong().coerceIn(0L, 50L),
                    "encryptedPayloadHash" to digest.take(128),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()

            logAuditEventInternal(firestore, uid, "RECORD_SYNCED", "Telemetry snapshot synced (SHA-256 ${digest.take(12)})")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteRecord(userId: String, recordId: String): Result<Unit> {
        val docPath = "users/$userId/records/$recordId"
        return try {
            val firestore = db ?: return Result.failure(
                IllegalStateException("Cloud sync inactive.")
            )
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch" }
            require(recordId.isNotBlank()) { "Record ID required" }
            firestore.document(docPath).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun logAuditEvent(userId: String, action: String, detail: String): Result<Unit> {
        return try {
            val firestore = db ?: return Result.success(Unit)
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch" }
            logAuditEventInternal(firestore, uid, action, detail)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "users/$userId/auditLogs")
            Result.failure(e)
        }
    }

    private suspend fun logAuditEventInternal(
        firestore: FirebaseFirestore,
        uid: String,
        action: String,
        detail: String,
    ) {
        val allowed = setOf("PROFILE_SAVED", "RECORD_SYNCED", "PDF_EXPORTED", "DATA_CLEARED")
        val safeAction = if (action in allowed) action else "RECORD_SYNCED"
        val safeDetail = detail.trim().ifEmpty { "Clinical action recorded" }.take(200)
        val docRef = firestore.collection("users").document(uid).collection("auditLogs").document()
        docRef.set(
            mapOf(
                "userId" to uid,
                "action" to safeAction,
                "detail" to safeDetail,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    fun hasLocalVault(): Boolean =
        cryptoManager.hasEncryptedCache("profile_latest.enc") ||
            cryptoManager.hasEncryptedCache("snapshot_latest.enc")

    fun clearLocalVault() {
        cryptoManager.clearVault()
    }
}
