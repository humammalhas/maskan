package app.maskan.chat

import android.app.LocaleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.appcompat.app.AppCompatDelegate
import app.maskan.chat.BuildConfig
import app.maskan.chat.data.repository.PreferenceRepository
import app.maskan.chat.navigation.Routes
import app.maskan.chat.ui.screens.AboutScreen
import app.maskan.chat.ui.screens.BackupScreen
import app.maskan.chat.ui.screens.ChatScreen
import app.maskan.chat.ui.screens.ConversationListScreen
import app.maskan.chat.ui.screens.FolderScreen
import app.maskan.chat.ui.screens.ProjectFileEditorScreen
import app.maskan.chat.ui.screens.SettingsScreen
import app.maskan.chat.ui.screens.PrivacyIntroScreen
import app.maskan.chat.ui.screens.PrivacyScreen
import app.maskan.chat.ui.screens.WelcomeScreen
import app.maskan.chat.ui.theme.MaskanTheme
import app.maskan.chat.ui.viewmodel.BackupViewModel
import app.maskan.chat.ui.viewmodel.ConversationListViewModel
import app.maskan.chat.ui.viewmodel.ProjectFile
import app.maskan.chat.ui.viewmodel.ProjectFilesViewModel
import app.maskan.chat.ui.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val app by lazy { application as MaskanApplication }

    /**
     * The conversation a notification asked for, waiting to be navigated to.
     *
     * Compose state rather than a nav call from onNewIntent: when the app is already running the
     * tap arrives at an activity whose NavHost is long since composed, and the only safe place to
     * navigate from is inside composition.
     */
    private var pendingConversationId by mutableStateOf<Long?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = conversationIdFrom(intent)
        if (BuildConfig.DEBUG) android.util.Log.d("MaskanNav", "onNewIntent conversationId=" + id)
        id?.let { pendingConversationId = it }
    }

    private fun conversationIdFrom(intent: Intent?): Long? =
        intent?.getLongExtra(EXTRA_CONVERSATION_ID, -1L)?.takeIf { it > 0 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (app.preferenceRepository.isBlockScreenshots()) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        val factory = MaskanViewModelFactory(app)
        val conversationListViewModel = ViewModelProvider(this, factory)[ConversationListViewModel::class.java]
        val settingsViewModel = ViewModelProvider(this, factory)[SettingsViewModel::class.java]
        val backupViewModel = ViewModelProvider(this, factory)[BackupViewModel::class.java]

        // Read the language from the resource configuration - the source the strings actually
        // resolve from. LocaleManager.applicationLocales misses a per-app language set from the
        // SYSTEM settings (or adb), which silently rendered Arabic with the DEFAULT typography -
        // hiding the Arabic-font layout bugs this theme flag exists to handle.
        val isArabic = resources.configuration.locales.get(0)?.language == "ar"

        pendingConversationId = conversationIdFrom(intent)

        val isFirstLaunch = !app.preferenceRepository.hasCompletedSetup()
        val needsPrivacyIntro = !app.preferenceRepository.hasSeenPrivacyIntro()
        val onboardingInProgress = app.preferenceRepository.isOnboardingInProgress()

        setContent {
            MaskanTheme(isArabic = isArabic) {
                AppNavigation(
                    conversationListViewModel = conversationListViewModel,
                    settingsViewModel = settingsViewModel,
                    backupViewModel = backupViewModel,
                    preferenceRepository = app.preferenceRepository,
                    onRestart = { recreate() },
                    isFirstLaunch = isFirstLaunch,
                    needsPrivacyIntro = needsPrivacyIntro,
                    onboardingInProgress = onboardingInProgress,
                    deepLinkConversationId = pendingConversationId,
                    onDeepLinkHandled = { pendingConversationId = null }
                )
            }
        }
    }

    companion object {
        /** Set by a "ready" notification so the tap lands in that chat, not on the list. */
        const val EXTRA_CONVERSATION_ID = "conversationId"
    }
}

@Composable
private fun AppNavigation(
    conversationListViewModel: ConversationListViewModel,
    settingsViewModel: SettingsViewModel,
    backupViewModel: BackupViewModel,
    preferenceRepository: PreferenceRepository,
    onRestart: () -> Unit,
    isFirstLaunch: Boolean,
    needsPrivacyIntro: Boolean = false,
    onboardingInProgress: Boolean = false,
    deepLinkConversationId: Long? = null,
    onDeepLinkHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val startDestination = when {
        isFirstLaunch -> Routes.WELCOME
        needsPrivacyIntro -> Routes.PRIVACY_INTRO
        // Resume the first-launch Settings step if onboarding was interrupted by a locale-change
        // restart, so the "Start Chatting" button isn't lost when switching language mid-onboarding.
        onboardingInProgress -> Routes.SETTINGS + "?firstLaunch=true"
        else -> Routes.CONVERSATION_LIST
    }

    // A "Video ready" tap while the user is mid-onboarding would drop them into a chat with no
    // key set and no way back to the setup they were in; the notification stays in the shade for
    // them to find afterwards instead.
    val deepLinkAllowed = startDestination == Routes.CONVERSATION_LIST
    LaunchedEffect(deepLinkConversationId, deepLinkAllowed) {
        val target = deepLinkConversationId
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "MaskanNav",
                "deepLink effect target=" + target + " allowed=" + deepLinkAllowed +
                    " start=" + startDestination
            )
        }
        if (target != null && deepLinkAllowed) {
            runCatching { navController.navigate(Routes.chatRoute(target)) { launchSingleTop = true } }
                .onFailure {
                    if (BuildConfig.DEBUG) android.util.Log.w("MaskanNav", "navigate failed", it)
                }
            onDeepLinkHandled()
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onGetStarted = {
                    preferenceRepository.setCompletedSetup()
                    navController.navigate(Routes.PRIVACY_INTRO) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.PRIVACY_INTRO) {
            PrivacyIntroScreen(
                onGotIt = {
                    preferenceRepository.setPrivacyIntroSeen()
                    preferenceRepository.setOnboardingInProgress(true)
                    navController.navigate(Routes.SETTINGS + "?firstLaunch=true") {
                        popUpTo(Routes.PRIVACY_INTRO) { inclusive = true }
                    }
                },
                onLearnMore = {
                    navController.navigate(Routes.PRIVACY)
                }
            )
        }

        composable(Routes.CONVERSATION_LIST) {
            ConversationListScreen(
                viewModel = conversationListViewModel,
                onNavigateToChat = { conversationId ->
                    navController.navigate(Routes.chatRoute(conversationId))
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                onOpenProjectFiles = { folderId ->
                    navController.navigate(Routes.folderRoute(folderId))
                },
                // The same editor the folder screen opens, entered straight from the new-folder
                // dialog, so Back lands on the list the folder was made from.
                onOpenFolderInstructions = { folderId ->
                    navController.navigate(Routes.projectFileRoute(folderId, ProjectFile.INSTRUCTIONS))
                }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("conversationId") { type = NavType.LongType })
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getLong("conversationId") ?: return@composable
            val app = LocalContext.current.applicationContext as MaskanApplication
            val chatViewModel = remember(conversationId) { app.provideChatViewModel() }
            ChatScreen(
                viewModel = chatViewModel,
                conversationId = conversationId,
                preferenceRepository = preferenceRepository,
                onNavigateBack = { navController.popBackStack() },
                // "Remember this" opens the file it just wrote to. The write is never silent.
                onOpenProjectMemory = { folderId ->
                    navController.navigate(Routes.projectFileRoute(folderId, ProjectFile.MEMORY))
                },
                // The header's folder chip: the same editor as the new-folder dialog's button.
                onOpenFolderInstructions = { folderId ->
                    navController.navigate(Routes.projectFileRoute(folderId, ProjectFile.INSTRUCTIONS))
                },
                // A fork from this chat. Pushed on top, so Back returns to the chat it came from.
                onOpenConversation = { id -> navController.navigate(Routes.chatRoute(id)) }
            )
        }

        composable(
            route = Routes.FOLDER,
            arguments = listOf(navArgument("folderId") { type = NavType.LongType })
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getLong("folderId") ?: return@composable
            val app = LocalContext.current.applicationContext as MaskanApplication
            val projectFilesViewModel = remember(folderId) { app.provideProjectFilesViewModel() }
            FolderScreen(
                viewModel = projectFilesViewModel,
                folderId = folderId,
                onNavigateBack = { navController.popBackStack() },
                onOpenFile = { file ->
                    navController.navigate(Routes.projectFileRoute(folderId, file))
                }
            )
        }

        composable(
            route = Routes.PROJECT_FILE,
            arguments = listOf(
                navArgument("folderId") { type = NavType.LongType },
                navArgument("file") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getLong("folderId") ?: return@composable
            val file = backStackEntry.arguments?.getString("file") ?: ProjectFile.INSTRUCTIONS
            val app = LocalContext.current.applicationContext as MaskanApplication
            // Keyed on both: Instructions and Memory are separate visits to separate files, and a
            // shared instance would carry the first file's draft into the second.
            val projectFilesViewModel = remember(folderId, file) { app.provideProjectFilesViewModel() }
            ProjectFileEditorScreen(
                viewModel = projectFilesViewModel,
                folderId = folderId,
                file = file,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.SETTINGS + "?firstLaunch={firstLaunch}",
            arguments = listOf(navArgument("firstLaunch") {
                type = NavType.BoolType
                defaultValue = false
            })
        ) { backStackEntry ->
            val isFirstLaunchSettings = backStackEntry.arguments?.getBoolean("firstLaunch") ?: false
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    if (isFirstLaunchSettings) {
                        preferenceRepository.setOnboardingInProgress(false)
                        navController.navigate(Routes.CONVERSATION_LIST) {
                            popUpTo(0) { inclusive = true }
                        }
                    } else if (!navController.popBackStack()) {
                        navController.navigate(Routes.CONVERSATION_LIST) {
                            popUpTo(Routes.SETTINGS) { inclusive = true }
                        }
                    }
                },
                onNavigateToAbout = { navController.navigate(Routes.ABOUT) },
                onNavigateToPrivacy = { navController.navigate(Routes.PRIVACY) },
                onNavigateToBackup = { navController.navigate(Routes.BACKUP) },
                onLocaleChanged = { onRestart() },
                onEditSharedMemory = {
                    navController.navigate(
                        Routes.projectFileRoute(ProjectFilesViewModel.GLOBAL_SCOPE, ProjectFile.MEMORY)
                    )
                },
                isFirstLaunch = isFirstLaunchSettings
            )
        }

        composable(Routes.BACKUP) {
            BackupScreen(
                viewModel = backupViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ABOUT) {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.PRIVACY) {
            PrivacyScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
