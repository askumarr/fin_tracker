package com.fintracker.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fintracker.app.ui.screens.accounts.AccountsScreen
import com.fintracker.app.ui.screens.addedit.AddEditScreen
import com.fintracker.app.ui.screens.backup.BackupScreen
import com.fintracker.app.ui.screens.categories.CategoriesScreen
import com.fintracker.app.ui.screens.dashboard.DashboardScreen
import com.fintracker.app.ui.screens.importcsv.ImportCsvScreen
import com.fintracker.app.ui.screens.more.MoreScreen
import com.fintracker.app.ui.screens.onboarding.OnboardingScreen
import com.fintracker.app.ui.screens.review.ReviewScreen
import com.fintracker.app.ui.screens.transactions.TransactionsScreen

object Routes {
    const val Onboarding = "onboarding"
    const val Dashboard = "dashboard"
    const val Transactions = "transactions"
    const val Categories = "categories"
    const val More = "more"
    const val Review = "review"
    const val Accounts = "accounts"
    const val ImportCsv = "import_csv"
    const val Backup = "backup"
    const val AddEdit = "add_edit?txnId={txnId}"
    fun addEdit(txnId: Long = -1L) = "add_edit?txnId=$txnId"
}

@Composable
fun FinTrackerNavHost(startOnboarding: Boolean) {
    val navController = rememberNavController()
    val start = if (startOnboarding) Routes.Onboarding else Routes.Dashboard
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    val showBottomBar = current in setOf(
        Routes.Dashboard,
        Routes.Transactions,
        Routes.Categories,
        Routes.More
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = current == Routes.Dashboard,
                        onClick = {
                            navController.navigate(Routes.Dashboard) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Home") }
                    )
                    NavigationBarItem(
                        selected = current == Routes.Transactions,
                        onClick = {
                            navController.navigate(Routes.Transactions) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.List, contentDescription = null) },
                        label = { Text("History") }
                    )
                    NavigationBarItem(
                        selected = current == Routes.Categories,
                        onClick = {
                            navController.navigate(Routes.Categories) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Category, contentDescription = null) },
                        label = { Text("Categories") }
                    )
                    NavigationBarItem(
                        selected = current == Routes.More,
                        onClick = {
                            navController.navigate(Routes.More) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.MoreHoriz, contentDescription = null) },
                        label = { Text("More") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = start,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.Onboarding) {
                OnboardingScreen(
                    onFinished = {
                        navController.navigate(Routes.Dashboard) {
                            popUpTo(Routes.Onboarding) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.Dashboard) {
                DashboardScreen(
                    onAdd = { navController.navigate(Routes.addEdit()) },
                    onReview = { navController.navigate(Routes.Review) },
                    onOpenTransaction = { id -> navController.navigate(Routes.addEdit(id)) }
                )
            }
            composable(Routes.Transactions) {
                TransactionsScreen(
                    onOpenTransaction = { id -> navController.navigate(Routes.addEdit(id)) }
                )
            }
            composable(Routes.Categories) { CategoriesScreen() }
            composable(Routes.More) {
                MoreScreen(
                    onAccounts = { navController.navigate(Routes.Accounts) },
                    onImport = { navController.navigate(Routes.ImportCsv) },
                    onBackup = { navController.navigate(Routes.Backup) },
                    onReview = { navController.navigate(Routes.Review) }
                )
            }
            composable(Routes.Review) {
                ReviewScreen(onEdit = { id -> navController.navigate(Routes.addEdit(id)) })
            }
            composable(Routes.Accounts) { AccountsScreen() }
            composable(Routes.ImportCsv) { ImportCsvScreen() }
            composable(Routes.Backup) { BackupScreen() }
            composable(
                route = Routes.AddEdit,
                arguments = listOf(
                    navArgument("txnId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    }
                )
            ) {
                AddEditScreen(onDone = { navController.popBackStack() })
            }
        }
    }
}
