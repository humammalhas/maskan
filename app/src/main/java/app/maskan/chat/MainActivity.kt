package app.maskan.chat

import android.app.LocaleManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.appcompat.app.AppCompatDelegate
import app.maskan.chat.data.repository.PreferenceRepository
import app.maskan.chat.navigation.Routes
import app.maskan.chat.ui.screens.AboutScreen
import app.maskan.chat.ui.screens.ChatScreen
import app.maskan.chat.ui.screens.ConversationListScreen
import app.maskan.chat.ui.screens.SettingsScreen
import app.maskan.chat.ui.screens.WelcomeScreen
import app.maskan.chat.ui.theme.MaskanTheme
import app.maskan.chat.ui.viewmodel.ConversationListViewModel
import app.maskan.chat.ui.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val app by lazy { application as MaskanApplication }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val factory = MaskanViewModelFactory(app)
        val conversationListViewModel = ViewModelProvider(this, factory)[ConversationListViewModel::class.java]
        val settingsViewModel = ViewModelProvider(this, factory)[SettingsViewModel::class.java]

        val isArabic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = getSystemService(LocaleManager::class.java)
            lm?.applicationLocales?.get(0)?.language == "ar"
        } else {
            AppCompatDelegate.getApplicationLocales().get(0)?.language == "ar"
        }

        val isFirstLaunch = !app.preferenceRepository.hasCompletedSetup()

        setContent {
            MaskanTheme(isArabic = isArabic) {
                AppNavigation(
                    conversationListViewModel = conversationListViewModel,
                    settingsViewModel = settingsViewModel,
                    preferenceRepository = app.preferenceRepository,
                    onRestart = { recreate() },
                    isFirstLaunch = isFirstLaunch
                )
            }
        }
    }
}

@Composable
private fun AppNavigation(
    conversationListViewModel: ConversationListViewModel,
    settingsViewModel: SettingsViewModel,
    preferenceRepository: PreferenceRepository,
    onRestart: () -> Unit,
    isFirstLaunch: Boolean
) {
    val navController = rememberNavController()
    val startDestination = if (isFirstLaunch) Routes.WELCOME else Routes.CONVERSATION_LIST

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onGetStarted = {
                    preferenceRepository.setCompletedSetup()
                    navController.navigate(Routes.SETTINGS + "?firstLaunch=true") {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
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
                onLocaleChanged = { onRestart() },
                isFirstLaunch = isFirstLaunchSettings
            )
        }

        composable(Routes.ABOUT) {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
