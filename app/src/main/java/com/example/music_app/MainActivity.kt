package com.example.music_app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.music_app.ui.theme.Music_APPTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val authDatabase = remember { AuthDatabase(this) }
            var themeMode by rememberSaveable { mutableStateOf("dark") }
            var languageMode by rememberSaveable { mutableStateOf("ru") }
            val darkTheme = when (themeMode) {
                "light" -> false
                "system" -> isSystemInDarkTheme()
                else -> true
            }
            val localizedContext = remember(languageMode) {
                val config = Configuration(resources.configuration)
                config.setLocale(Locale.forLanguageTag(if (languageMode == "en") "en" else "ru"))
                createConfigurationContext(config)
            }
            CompositionLocalProvider(LocalContext provides localizedContext) {
                Music_APPTheme(darkTheme = darkTheme) {
                    DanceDeckApp(
                        authDatabase = authDatabase,
                        onSettingsChanged = {
                            themeMode = it.theme
                            languageMode = it.language
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DanceDeckApp(authDatabase: AuthDatabase, onSettingsChanged: (UserSettings) -> Unit) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val socialAuthManager = remember { SocialAuthManager() }
    val backendApi = remember { BackendApi() }
    val sessionStore = remember { BackendSessionStore(context) }
    var screen by rememberSaveable { mutableStateOf(AuthScreen.Login) }
    var pendingEmail by rememberSaveable { mutableStateOf("") }
    var devCode by rememberSaveable { mutableStateOf("") }
    var authToken by rememberSaveable { mutableStateOf(sessionStore.token().orEmpty()) }
    var currentUser by remember { mutableStateOf<User?>(null) }

    fun showMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    LaunchedEffect(Unit) {
        if (authToken.isBlank()) return@LaunchedEffect
        val restored = withContext(Dispatchers.IO) {
            runCatching { backendApi.me(authToken) }
        }
        restored
            .onSuccess { user ->
                authDatabase.upsertBackendUser(user)
                currentUser = user
                screen = AuthScreen.App
            }
            .onFailure {
                sessionStore.clear()
                authToken = ""
            }
    }

    fun issueLoginCode(user: User) {
        currentUser = user
        pendingEmail = user.email
        devCode = ""
        showMessage(context.getString(R.string.code_sent, user.email))
        screen = AuthScreen.Code
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF07090D), Color(0xFF07090D))
                    )
                )
        ) {
            when (screen) {
                AuthScreen.Login -> LoginScreen(
                    onLogin = { email, password ->
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { backendApi.login(email, password) }
                            }
                            result
                                .onSuccess { user ->
                                    authDatabase.upsertBackendUser(user)
                                    issueLoginCode(user)
                                }
                                .onFailure { showMessage(context.getString(R.string.snackbar_bad_credentials)) }
                        }
                    },
                    onSocialLogin = { provider, email ->
                        socialAuthManager.signIn(SocialAuthRequest(provider, email))
                            .onSuccess { result ->
                                scope.launch {
                                    val userResult = withContext(Dispatchers.IO) {
                                        runCatching {
                                            runCatching { backendApi.sendCode(result.email) }
                                            backendApi.register(
                                                name = result.provider,
                                                email = result.email,
                                                phone = "",
                                                password = "social-${System.currentTimeMillis()}"
                                            )
                                        }.recoverCatching {
                                            backendApi.sendCode(result.email)
                                            User(0, result.provider, result.email, "")
                                        }
                                    }
                                    userResult
                                        .onSuccess { user -> issueLoginCode(user) }
                                        .onFailure { showMessage(context.getString(R.string.login_failed)) }
                                }
                            }
                            .onFailure { showMessage(context.getString(R.string.social_email_required)) }
                    },
                    onRegisterClick = { screen = AuthScreen.Register }
                )

                AuthScreen.Register -> RegisterScreen(
                    onRegister = { name, email, phone, password ->
                        if (name.isBlank() || phone.isBlank() || !email.contains("@") || password.length < 6) {
                            showMessage(context.getString(R.string.snackbar_fill_register))
                        } else {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val user = backendApi.register(name, email, phone, password)
                                        backendApi.sendCode(user.email)
                                        user
                                    }
                                }
                                result
                                    .onSuccess { user ->
                                        authDatabase.upsertBackendUser(user)
                                        issueLoginCode(user)
                                    }
                                    .onFailure { showMessage(context.getString(R.string.register_failed)) }
                            }
                        }
                    },
                    onSocialRegister = { provider, email ->
                        socialAuthManager.signIn(SocialAuthRequest(provider, email))
                            .onSuccess { result ->
                                scope.launch {
                                    val userResult = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val user = backendApi.register(
                                                name = result.provider,
                                                email = result.email,
                                                phone = "",
                                                password = "social-${System.currentTimeMillis()}"
                                            )
                                            backendApi.sendCode(user.email)
                                            user
                                        }
                                    }
                                    userResult
                                        .onSuccess { user ->
                                            authDatabase.upsertBackendUser(user)
                                            issueLoginCode(user)
                                        }
                                        .onFailure { showMessage(context.getString(R.string.login_failed)) }
                                }
                            }
                            .onFailure { showMessage(context.getString(R.string.social_email_required)) }
                    },
                    onLoginClick = { screen = AuthScreen.Login }
                )

                AuthScreen.Code -> CodeScreen(
                    email = pendingEmail,
                    codePreview = devCode,
                    onConfirm = { code ->
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { backendApi.verifyCode(pendingEmail, code) }
                            }
                            result
                                .onSuccess { session ->
                                    authToken = session.token
                                    sessionStore.save(session.token)
                                    authDatabase.upsertBackendUser(session.user)
                                    currentUser = session.user
                                    screen = AuthScreen.App
                                    showMessage(context.getString(R.string.snackbar_welcome))
                                }
                                .onFailure { showMessage(context.getString(R.string.snackbar_bad_code)) }
                        }
                    },
                    onResend = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { backendApi.sendCode(pendingEmail) }
                            }
                            result
                                .onSuccess { showMessage(context.getString(R.string.new_code_sent, pendingEmail)) }
                                .onFailure { showMessage(context.getString(R.string.email_server_missing)) }
                        }
                    },
                    onBack = { screen = AuthScreen.Login }
                )

                AuthScreen.App -> MusicShell(
                    user = currentUser,
                    authDatabase = authDatabase,
                    backendApi = backendApi,
                    authToken = authToken,
                    showMessage = ::showMessage,
                    onSettingsChanged = onSettingsChanged,
                    onLogout = {
                        sessionStore.clear()
                        authToken = ""
                        currentUser = null
                        pendingEmail = ""
                        devCode = ""
                        screen = AuthScreen.Login
                    }
                )
            }
        }
    }
}
