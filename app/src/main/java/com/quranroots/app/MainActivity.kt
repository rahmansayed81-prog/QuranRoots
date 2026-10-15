package com.quranroots.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.quranroots.app.ui.components.AddToStudyController
import com.quranroots.app.ui.components.AddToStudyDialog
import com.quranroots.app.ui.components.LocalAddToStudy
import com.quranroots.app.ui.components.AnnotationsController
import com.quranroots.app.ui.components.AnnotateSheet
import com.quranroots.app.ui.components.LocalAnnotations
import com.quranroots.app.ui.components.LocalAnnotationsController
import com.quranroots.app.data.repository.Annotations
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quranroots.app.ui.screens.study.StudyScreen
import com.quranroots.app.ui.screens.sync.DriveSyncScreen
import com.quranroots.app.ui.screens.home.HomeScreen
import com.quranroots.app.ui.screens.letters.LetterDetailScreen
import com.quranroots.app.ui.screens.letters.LettersScreen
import com.quranroots.app.ui.screens.letters.MuqattaatScreen
import com.quranroots.app.ui.screens.rootdetail.RootDetailScreen
import com.quranroots.app.ui.screens.search.SearchScreen
import com.quranroots.app.ui.screens.workspace.WorkspaceScreen
import com.quranroots.app.ui.theme.QuranRootsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QuranRootsTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppRoot()
                    }
                }
            }
        }
    }
}

private object Routes {
    const val Home = "home"
    const val Search = "search"
    const val Letters = "letters"
    const val Workspace = "workspace"
    const val RootDetail = "root/{rootId}"
    fun rootDetail(rootId: Long) = "root/$rootId"
    const val LetterDetail = "letter/{letter}"
    fun letterDetail(letter: String) = "letter/$letter"
    const val Muqattaat = "muqattaat"
    const val Sync = "sync"
    const val Study = "study/{studyId}"
    fun study(id: Long) = "study/$id"
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    val app = LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val addToStudy = remember { AddToStudyController(app.workspaceRepo, scope, snackbar) }
    val annotate = remember { AnnotationsController(app.workspaceRepo, scope) }
    val annotations by app.workspaceRepo.annotations
        .collectAsStateWithLifecycle(initialValue = Annotations())

    CompositionLocalProvider(
        LocalAddToStudy provides addToStudy,
        LocalAnnotationsController provides annotate,
        LocalAnnotations provides annotations
    ) {
    AddToStudyDialog(addToStudy)
    AnnotateSheet(annotate)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val showBar = current?.route in setOf(Routes.Home, Routes.Search, Routes.Letters, Routes.Workspace)
            if (showBar) {
                NavigationBar {
                    listOf(
                        Triple(Routes.Home, Icons.Outlined.Home, R.string.tab_home),
                        Triple(Routes.Search, Icons.Outlined.Search, R.string.tab_search),
                        Triple(Routes.Letters, Icons.Outlined.Translate, R.string.tab_letters),
                        Triple(Routes.Workspace, Icons.Outlined.Bookmarks, R.string.tab_workspace),
                    ).forEach { (route, icon, label) ->
                        NavigationBarItem(
                            selected = current?.hierarchy?.any { it.route == route } == true,
                            onClick = {
                                nav.navigate(route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(stringResource(label)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.Home,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.Home) {
                HomeScreen(onRootClick = { id -> nav.navigate(Routes.rootDetail(id)) })
            }
            composable(Routes.Search) {
                SearchScreen(onRootClick = { id -> nav.navigate(Routes.rootDetail(id)) })
            }
            composable(Routes.Letters) {
                LettersScreen(
                    onLetterClick = { ch -> nav.navigate(Routes.letterDetail(ch)) },
                    onMuqattaatClick = { nav.navigate(Routes.Muqattaat) }
                )
            }
            composable(Routes.Workspace) {
                WorkspaceScreen(
                    onRootClick = { id -> nav.navigate(Routes.rootDetail(id)) },
                    onStudyClick = { id -> nav.navigate(Routes.study(id)) },
                    onSyncClick = { nav.navigate(Routes.Sync) }
                )
            }
            composable(Routes.Sync) {
                DriveSyncScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.Study) { entry ->
                val id = entry.arguments?.getString("studyId")?.toLongOrNull() ?: return@composable
                StudyScreen(
                    studyId = id,
                    onBack = { nav.popBackStack() },
                    onRootClick = { rid -> nav.navigate(Routes.rootDetail(rid)) }
                )
            }
            composable(Routes.RootDetail) { entry ->
                val rootId = entry.arguments?.getString("rootId")?.toLongOrNull() ?: return@composable
                RootDetailScreen(
                    rootId = rootId,
                    onBack = { nav.popBackStack() },
                    onRelatedRoot = { id -> nav.navigate(Routes.rootDetail(id)) }
                )
            }
            composable(Routes.LetterDetail) { entry ->
                val ch = entry.arguments?.getString("letter") ?: return@composable
                LetterDetailScreen(
                    letter = ch,
                    onBack = { nav.popBackStack() },
                    onRootClick = { id -> nav.navigate(Routes.rootDetail(id)) }
                )
            }
            composable(Routes.Muqattaat) {
                MuqattaatScreen(
                    onBack = { nav.popBackStack() },
                    onLetterClick = { ch -> nav.navigate(Routes.letterDetail(ch)) }
                )
            }
        }
    }
    } // CompositionLocalProvider
}
