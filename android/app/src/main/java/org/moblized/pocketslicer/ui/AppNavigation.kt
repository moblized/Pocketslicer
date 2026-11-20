package org.moblized.pocketslicer.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.moblized.pocketslicer.AppContainer

@Composable
fun AppNavigation(container: AppContainer, startConfigured: Boolean, dark: Boolean) {
    val nav = rememberNavController()
    val start = remember { if (startConfigured) "home" else "setup" }

    fun openPlate(modelId: String, jobId: String?) {
        val route = "plate/$modelId" + if (jobId != null) "?job=${Uri.encode(jobId)}" else ""
        nav.navigate(route)
    }

    NavHost(navController = nav, startDestination = start) {
        composable("setup") {
            SetupScreen(
                container = container,
                onDone = {
                    nav.navigate("home") { popUpTo("setup") { inclusive = true } }
                },
            )
        }
        composable("home") {
            HomeScreen(
                container = container,
                onOpenModel = { modelId -> openPlate(modelId, null) },
                onOpenJob = { jobId -> nav.navigate("job/$jobId") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable(
            route = "plate/{modelId}?job={jobId}",
            arguments = listOf(
                navArgument("modelId") { type = NavType.StringType },
                navArgument("jobId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            PlateScreen(
                container = container,
                modelId = entry.arguments?.getString("modelId").orEmpty(),
                fromJobId = entry.arguments?.getString("jobId"),
                dark = dark,
                onBack = { nav.popBackStack() },
                onSliced = { jobId -> nav.navigate("job/$jobId") },
            )
        }
        composable("job/{jobId}", arguments = listOf(navArgument("jobId") { type = NavType.StringType })) { entry ->
            val jobId = entry.arguments?.getString("jobId").orEmpty()
            JobScreen(
                container = container,
                jobId = jobId,
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack("home", inclusive = false) },
                onEdit = { modelId ->
                    val previous = nav.previousBackStackEntry?.destination?.route.orEmpty()
                    if (previous.startsWith("plate/")) nav.popBackStack() else openPlate(modelId, jobId)
                },
            )
        }
        composable("settings") {
            SettingsScreen(container = container, onBack = { nav.popBackStack() })
        }
    }
}
