package app.maskan.chat

import android.app.LocaleManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.appcompat.app.AppCompatDelegate
import app.maskan.chat.data.repository.ChatRepository
import app.maskan.chat.data.repository.KeyRepository
import app.maskan.chat.data.repository.LocaleRepository
import app.maskan.chat.data.repository.PreferenceRepository
import app.maskan.chat.navigation.Routes
import app.maskan.chat.ui.screens.AboutScreen
import app.maskan.chat.ui.screens.ChatScreen
import app.maskan.chat.ui.screens.ConversationListScreen
import app.maskan.chat.ui.screens.SettingsScreen
import app.maskan.chat.ui.screens.WelcomeScreen
import app.maskan.chat.ui.theme.MaskanTheme
import app.maskan.chat.ui.viewmodel.ChatViewModel
import app.maskan.chat.ui.viewmodel.ConversationListViewModel

class MainActivity : ComponentActivity() {

    private val app by lazy { application as MaskanApplication }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val conversationListViewModel = app.provideConversationListViewModel()
        val chatViewModel = app.provideChatViewModel()

        val isArabic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = getSystemService(LocaleManager::class.java)
            lm?.applicationLocales?.get(0)?.language == "ar"
        } else {
            AppCompatDelegate.getApplicationLocales().get(0)?.language == "ar"
        }

        val isFirstLaunch = app.keyRepository.getAllStoredProviderIds().isEmpty()

        setContent {
            MaskanTheme(isArabic = isArabic) {
                AppNavigation(
                    conversationListViewModel = conversationListViewModel,
                    chatViewModel = chatViewModel,
                    keyRepository = app.keyRepository,
                    localeRepository = app.localeRepository,
                    preferenceRepository = app.preferenceRepository,
                    chatRepository = app.chatRepository,
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
    chatViewModel: ChatViewModel,
    keyRepository: KeyRepository,
    localeRepository: LocaleRepository,
    preferenceRepository: PreferenceRepository,
    chatRepository: ChatRepository,
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
                keyRepository = keyRepository,
                localeRepository = localeRepository,
                preferenceRepository = preferenceRepository,
                chatRepository = chatRepository,
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
                currentModel = chatViewModel.uiState.value.selectedModel,
                onModelChanged = { model -> chatViewModel.setSelectedModel(model) },
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
