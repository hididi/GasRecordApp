package com.hididi.gaslog.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StackedBarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hididi.gaslog.ui.screens.BillingScreen
import com.hididi.gaslog.ui.screens.CaptureScreen
import com.hididi.gaslog.ui.screens.HomeScreen
import com.hididi.gaslog.ui.screens.ReadingDetailScreen
import com.hididi.gaslog.ui.screens.RecordsScreen
import com.hididi.gaslog.ui.screens.ReviewScreen
import com.hididi.gaslog.ui.screens.SettingsScreen
import com.hididi.gaslog.ui.screens.StatsScreen
import com.hididi.gaslog.ui.vm.GasLogViewModel

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "首页", Icons.Filled.Home),
    Tab("stats", "统计", Icons.Filled.StackedBarChart),
    Tab("billing", "费用", Icons.Filled.Payments),
    Tab("settings", "设置", Icons.Filled.Settings),
)

@Composable
fun GasLogRoot(
    vm: GasLogViewModel,
    incomingUri: Uri?,
    onIncomingConsumed: () -> Unit,
) {
    val nav = rememberNavController()
    // 支持多选：一次选多张，逐张核对后统一确认
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(12),
    ) { uris ->
        if (uris.isNotEmpty()) {
            vm.importPhotos(uris)
            nav.navigate("review")
        }
    }
    val launchGallery: () -> Unit = {
        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            vm.importPhotos(listOf(incomingUri))
            nav.navigate("review")
            onIncomingConsumed()
        }
    }

    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    nav.navigate(tab.route) {
                                        popUpTo("home")
                                        launchSingleTop = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(if (showBottomBar) padding else androidx.compose.foundation.layout.PaddingValues(0.dp)),
        ) {
            composable("home") {
                HomeScreen(
                    vm = vm,
                    onCapture = { nav.navigate("capture") },
                    onImport = launchGallery,
                    onManual = {
                        vm.startManual()
                        nav.navigate("review")
                    },
                    onOpenRecords = { nav.navigate("records") },
                    onOpenBilling = { nav.navigate("billing") },
                    onOpenDetail = { id -> nav.navigate("detail/$id") },
                )
            }
            composable("stats") { StatsScreen(vm) }
            composable("billing") { BillingScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
            composable("records") {
                RecordsScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onOpenDetail = { id -> nav.navigate("detail/$id") },
                )
            }
            composable("detail/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L
                ReadingDetailScreen(
                    vm = vm,
                    readingId = id,
                    onBack = { nav.popBackStack() },
                    onEdit = { editId ->
                        vm.startEdit(editId)
                        nav.navigate("review")
                    },
                )
            }
            composable("capture") {
                CaptureScreen(
                    vm = vm,
                    onCaptured = { nav.navigate("review") },
                    onCancel = { nav.popBackStack() },
                    onPickGallery = launchGallery,
                )
            }
            composable("review") {
                ReviewScreen(
                    vm = vm,
                    onSaved = {
                        nav.popBackStack("home", inclusive = false)
                    },
                    onCancel = { nav.popBackStack() },
                )
            }
        }
    }
}
