package com.stridetech.coreai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stridetech.coreai.ui.modelhub.ModelHubScreen
import com.stridetech.coreai.ui.playground.PlaygroundScreen
import dagger.hilt.android.AndroidEntryPoint

private const val ROUTE_PLAYGROUND = "playground"
private const val ROUTE_MODEL_HUB = "model_hub"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    if (currentRoute == ROUTE_MODEL_HUB) "Model Hub"
                                    else "Core AI Playground"
                                )
                            },
                            navigationIcon = {
                                if (currentRoute == ROUTE_MODEL_HUB) {
                                    IconButton(onClick = { navController.popBackStack() }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                            contentDescription = "Back"
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (currentRoute != ROUTE_MODEL_HUB) {
                                    IconButton(onClick = { navController.navigate(ROUTE_MODEL_HUB) }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Build,
                                            contentDescription = "Model Hub"
                                        )
                                    }
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_PLAYGROUND,
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    ) {
                        composable(ROUTE_PLAYGROUND) {
                            PlaygroundScreen(
                                onNavigateToModelHub = { navController.navigate(ROUTE_MODEL_HUB) }
                            )
                        }
                        composable(ROUTE_MODEL_HUB) { ModelHubScreen() }
                    }
                }
            }
        }
    }
}