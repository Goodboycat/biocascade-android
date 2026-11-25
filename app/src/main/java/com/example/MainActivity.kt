package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.auth.AuthScreen
import com.example.data.ClinicalRecordsRepository
import com.example.data.EncryptedPdfExporter
import com.example.data.KeystoreCryptoManager
import com.example.data.PreferencesRepository
import com.example.medicalweb.MedicalWebRepository
import com.example.ui.MainDashboard
import com.example.ui.theme.BioCascadeTheme
import com.example.ui.theme.DashboardSkin
import com.example.viewmodel.PhysiologyViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PDFBoxResourceLoader.init(applicationContext)
        setContent {
            AppNavigation()
        }
    }
}

@Composable
fun AppNavigation(auth: FirebaseAuth = Firebase.auth) {
    var currentUser by remember { mutableStateOf(auth.currentUser) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            currentUser = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    val user = currentUser
    if (user == null) {
        BioCascadeTheme(skin = DashboardSkin.CLINICAL_OBSIDIAN) {
            AuthScreen(onAuthSuccess = { currentUser = auth.currentUser })
        }
    } else {
        val uid = user.uid
        key(uid) {
            val viewModel: PhysiologyViewModel = viewModel(
                key = uid,
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY]) {
                            "APPLICATION_KEY missing from CreationExtras"
                        }
                        val databaseId = app.getString(R.string.firestore_database_id)
                        val db = FirebaseFirestore.getInstance(databaseId)
                        val crypto = KeystoreCryptoManager(app)
                        PhysiologyViewModel(
                            recordsRepository = ClinicalRecordsRepository(db, crypto, auth),
                            medicalWebRepository = MedicalWebRepository(),
                            preferencesRepository = PreferencesRepository(app),
                            pdfExporter = EncryptedPdfExporter(app),
                            currentUserId = uid,
                        )
                    }
                },
            )
            val activeSkin by viewModel.selectedSkin.collectAsStateWithLifecycle()
            BioCascadeTheme(skin = activeSkin) {
                MainDashboard(
                    viewModel = viewModel,
                    onSignedOut = { currentUser = auth.currentUser },
                )
            }
        }
    }
}
