package app.haul

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.haul.data.AppSettings
import app.haul.data.DownloadEntity
import app.haul.data.MediaType
import app.haul.data.Status
import app.haul.player.AudioPlayerScreen
import app.haul.player.VideoPlayerScreen
import app.haul.ui.components.BottomNav
import app.haul.ui.components.SnackHost
import app.haul.ui.components.SnackState
import app.haul.ui.components.Tab
import app.haul.ui.grab.AllFormatsScreen
import app.haul.ui.grab.FormatSheet
import app.haul.ui.grab.GrabScreen
import app.haul.ui.grab.GrabViewModel
import app.haul.ui.grab.PlaylistScreen
import app.haul.ui.library.DeleteDialog
import app.haul.ui.library.ItemActionsSheet
import app.haul.ui.library.LibraryScreen
import app.haul.ui.library.LibraryViewModel
import app.haul.ui.library.RenameDialog
import app.haul.ui.library.showMissing
import app.haul.ui.onboarding.OnboardingScreen
import app.haul.ui.onboarding.PermissionsScreen
import app.haul.ui.queue.QueueScreen
import app.haul.ui.queue.QueueViewModel
import app.haul.ui.settings.AboutScreen
import app.haul.ui.settings.EngineScreen
import app.haul.ui.settings.LicencesScreen
import app.haul.ui.settings.SettingsScreen
import app.haul.ui.settings.SettingsViewModel
import app.haul.ui.theme.Haul
import app.haul.ui.theme.HaulTheme
import app.haul.ui.update.InstallScreen
import app.haul.ui.update.UpdatePromptSheet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Intents that arrive while the app is open (notifications, shares, the media session). */
    private val pending = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pending.value = intent
        setContent {
            val g = remember { HaulApp.graph(this) }
            val settings by g.settings.settings.collectAsState(null)
            val s = settings
            HaulTheme(s?.theme ?: app.haul.data.ThemeMode.DARK, s?.wallpaperColours ?: false) {
                Box(Modifier.fillMaxSize().background(Haul.colors.ground)) {
                    if (s != null) App(s, pending)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pending.value = intent
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_ITEM = "item"
        const val EXTRA_URL = "url"
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}

private object Routes {
    const val ONBOARDING = "onboarding"
    const val PERMISSIONS = "permissions"
    const val MAIN = "main"
    const val ALL_FORMATS = "all-formats"
    const val PLAYLIST = "playlist"
    const val VIDEO = "video/{id}"
    const val AUDIO = "audio/{id}"
    const val SETTINGS = "settings"
    const val ENGINE = "engine"
    const val ABOUT = "about"
    const val LICENCES = "licences"
    const val INSTALL = "install"
}

@Composable
private fun App(s: AppSettings, pending: MutableStateFlow<Intent?>) {
    val g = HaulApp.graph(androidx.compose.ui.platform.LocalContext.current)
    val nav = rememberNavController()
    val snack = remember { SnackState() }
    val scope = rememberCoroutineScope()
    val grab: GrabViewModel = viewModel()
    val queue: QueueViewModel = viewModel()
    val library: LibraryViewModel = viewModel()
    val settingsVm: SettingsViewModel = viewModel()
    var tab by rememberSaveable { mutableStateOf(Tab.Grab) }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route

    val all by remember { g.dao.observeAll() }.collectAsState(emptyList())
    val libraryItems by library.items.collectAsState()

    fun openItem(item: DownloadEntity) {
        if (item.status != Status.DONE) { tab = Tab.Queue; nav.toMain(); return }
        if (!library.exists(item)) { showMissing(item, library, snack); return }
        nav.navigate(if (item.mediaType == MediaType.AUDIO) "audio/${item.id}" else "video/${item.id}") { launchSingleTop = true }
    }

    fun regrab(url: String) {
        tab = Tab.Grab
        nav.toMain()
        grab.submit(url) { nav.navigate(Routes.PLAYLIST) { launchSingleTop = true } }
    }

    // App update: offer once per launch; a required update takes over the app.
    val available by g.updater.available.collectAsState()
    var promptDismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val day = 12 * 60 * 60 * 1000L
        if (s.onboarded && s.appUpdateCheck && g.updater.configured && System.currentTimeMillis() - s.appLastCheck > day) {
            runCatching { g.updater.check() }
            g.settings.setAppLastCheck(System.currentTimeMillis())
        }
    }
    val info = available
    if (info != null && info.required && s.onboarded) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) { InstallScreen(info, required = true, onLater = {}) }
        return
    }

    // Notifications, shares and the media session land here.
    val intent by pending.collectAsState()
    LaunchedEffect(intent) {
        val i = intent ?: return@LaunchedEffect
        pending.value = null
        if (!s.onboarded) return@LaunchedEffect
        val url = i.getStringExtra(MainActivity.EXTRA_URL)
            ?: i.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        when {
            url != null -> regrab(url)
            i.getBooleanExtra(MainActivity.EXTRA_OPEN_PLAYER, false) -> {
                tab = Tab.Library
                nav.toMain()
            }
            i.hasExtra(MainActivity.EXTRA_TAB) -> {
                val id = i.getLongExtra(MainActivity.EXTRA_ITEM, 0)
                val item = if (id > 0) g.dao.get(id) else null
                when (i.getStringExtra(MainActivity.EXTRA_TAB)) {
                    "library" -> { tab = Tab.Library; nav.toMain(); if (item != null && item.status == Status.DONE) openItem(item) }
                    else -> { tab = Tab.Queue; nav.toMain() }
                }
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) settingsVm.setFolder(uri)
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            nav,
            startDestination = if (s.onboarded) Routes.MAIN else Routes.ONBOARDING,
            modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding(),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onNext = { nav.navigate(Routes.PERMISSIONS) },
                    onSkip = { scope.launch { g.settings.setOnboarded() }; nav.toMain(clear = true) },
                    onTerms = { nav.navigate(Routes.ABOUT) },
                )
            }
            composable(Routes.PERMISSIONS) {
                PermissionsScreen(
                    saveLabel = s.saveLabel,
                    onBack = { nav.popBackStack() },
                    onPickFolder = { folderPicker.launch(null) },
                    onDone = { scope.launch { g.settings.setOnboarded() }; nav.toMain(clear = true) },
                )
            }
            composable(Routes.MAIN) {
                BackHandler(tab != Tab.Grab) { tab = Tab.Grab }
                val queueSections by queue.sections.collectAsState()
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            Tab.Grab -> GrabScreen(
                                vm = grab,
                                recents = all.take(3),
                                defaultVideo = "${s.videoHeight}p ${s.videoContainer.uppercase()}",
                                onSettings = { nav.navigate(Routes.SETTINGS) },
                                onSeeAll = { tab = Tab.Library },
                                onOpenItem = ::openItem,
                                onPlaylist = { nav.navigate(Routes.PLAYLIST) { launchSingleTop = true } },
                                onQueued = { snack.show("Added to the queue.", "View") { tab = Tab.Queue } },
                                onSnack = { snack.show(it) },
                            )
                            Tab.Queue -> QueueScreen(queue, snack, onOpenItem = ::openItem, onGrab = { tab = Tab.Grab })
                            Tab.Library -> LibraryScreen(
                                library, snack, s.saveLabel,
                                onPlay = ::openItem,
                                onGrab = { tab = Tab.Grab },
                            )
                        }
                    }
                    BottomNav(
                        tab,
                        queueBadge = queueSections.active.size + queueSections.waiting.size,
                        queueAlert = queueSections.failed.isNotEmpty(),
                    ) { tab = it }
                }
            }
            composable(Routes.ALL_FORMATS) {
                AllFormatsScreen(grab, onBack = { nav.popBackStack() }, onQueued = { nav.toMain(); tab = Tab.Queue })
            }
            composable(Routes.PLAYLIST) {
                PlaylistScreen(grab, s, onBack = { nav.popBackStack() }, onQueued = { nav.toMain(); tab = Tab.Queue })
            }
            composable(Routes.VIDEO, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0
                PlayerRoute(id, nav) { item ->
                    VideoPlayerScreen(
                        item, snack,
                        onClose = { nav.popBackStack() },
                        onDelete = { library.deleting = it },
                        onRegrab = { regrab(it) },
                    )
                }
            }
            composable(Routes.AUDIO, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0
                PlayerRoute(id, nav) { item ->
                    val audio = libraryItems.orEmpty().filter { it.mediaType == MediaType.AUDIO && library.exists(it) }
                    AudioPlayerScreen(item, audio, snack, onClose = { nav.popBackStack() }, onMore = { library.actionsFor = it })
                }
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settingsVm, snack,
                    onBack = { nav.popBackStack() },
                    onEngine = { nav.navigate(Routes.ENGINE) },
                    onAbout = { nav.navigate(Routes.ABOUT) },
                )
            }
            composable(Routes.ENGINE) { EngineScreen(settingsVm, snack, onBack = { nav.popBackStack() }) }
            composable(Routes.ABOUT) { AboutScreen(settingsVm, onBack = { nav.popBackStack() }, onLicences = { nav.navigate(Routes.LICENCES) }) }
            composable(Routes.LICENCES) { LicencesScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.INSTALL) {
                val i = available
                if (i == null) LaunchedEffect(Unit) { nav.popBackStack() }
                else InstallScreen(i, required = false, onLater = { nav.popBackStack() })
            }
        }

        // Overlays: sheets and dialogs draw over everything, including the bottom bar.
        FormatSheet(
            grab, s.saveLabel,
            onAllFormats = { grab.sheetOpen = false; nav.navigate(Routes.ALL_FORMATS) { launchSingleTop = true } },
            onQueued = { snack.show("Added to the queue.", "View") { tab = Tab.Queue; nav.toMain() } },
        )
        ItemActionsSheet(library, snack, onPlay = ::openItem, onRegrab = { regrab(it) })
        RenameDialog(library, snack)
        DeleteDialog(library, snack)
        UpdatePromptSheet(
            info,
            visible = info != null && !promptDismissed && s.onboarded && info.versionCode != s.skippedVersionCode,
            onUpdate = { promptDismissed = true; nav.navigate(Routes.INSTALL) { launchSingleTop = true } },
            onLater = { promptDismissed = true },
            onSkip = {
                promptDismissed = true
                info?.let { scope.launch { g.settings.setSkippedVersion(it.versionCode) } }
            },
        )
        SnackHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = if (route == Routes.MAIN) 84.dp else 16.dp))
    }
}

/** Loads a Library item for a player and leaves the screen if the item goes away (deleted). */
@Composable
private fun PlayerRoute(id: Long, nav: NavHostController, content: @Composable (DownloadEntity) -> Unit) {
    val g = HaulApp.graph(androidx.compose.ui.platform.LocalContext.current)
    var loaded by remember { mutableStateOf(false) }
    val item by remember(id) { g.dao.observe(id) }.collectAsState(null)
    LaunchedEffect(item) {
        if (item != null) loaded = true
        else if (loaded) nav.popBackStack()
    }
    item?.let { content(it) }
}

private fun NavHostController.toMain(clear: Boolean = false) {
    if (clear) {
        navigate(Routes.MAIN) { popUpTo(0) { inclusive = true } }
    } else if (!popBackStack(Routes.MAIN, inclusive = false)) {
        navigate(Routes.MAIN) { launchSingleTop = true }
    }
}
