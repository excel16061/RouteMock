package com.routemock.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routemock.app.R
import com.routemock.app.RouteMockApp
import com.routemock.app.simulation.opensOnSimulation

private enum class Section(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    MAP(R.string.section_map, R.drawable.ic_map),
    ROUTE(R.string.section_route, R.drawable.ic_route),
    SIMULATION(R.string.section_simulation, R.drawable.ic_simulation),
    SAVED(R.string.section_saved, R.drawable.ic_saved),
    MORE(R.string.section_more, R.drawable.ic_more),
}

@Composable
fun RouteMockRoot(viewModel: RouteMockViewModel) {
    val receipt by viewModel.consentReceipt.collectAsStateWithLifecycle()
    if (!viewModel.requiredConsentGranted(receipt)) {
        OnboardingScreen(viewModel)
        return
    }
    MainContent(viewModel)
}

@Composable
private fun MainContent(viewModel: RouteMockViewModel) {
    // After a force-stop or crash, open where the recovery notice is shown.
    var section by rememberSaveable {
        mutableStateOf(if (opensOnSimulation(viewModel.simulation.value)) Section.SIMULATION else Section.MAP)
    }

    // The user may have changed settings in another app while we were away.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshReadiness() }

    // Location is observed only while the app is visible, never from the service.
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    val tracker = (LocalContext.current.applicationContext as RouteMockApp).deviceLocationTracker
    LifecycleStartEffect(readiness.locationPermissionGranted) {
        tracker.start()
        viewModel.startPolling()
        onStopOrDispose {
            tracker.stop()
            viewModel.stopPolling()
        }
    }
    RemoteStartDialog(viewModel)

    Scaffold(
        bottomBar = {
            NavigationBar {
                Section.entries.forEach { item ->
                    NavigationBarItem(
                        selected = section == item,
                        onClick = { section = item },
                        icon = { Icon(painterResource(item.icon), contentDescription = null) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            when (section) {
                Section.MAP -> MapScreen(viewModel)
                Section.ROUTE -> RouteScreen(viewModel, onGoToSimulation = { section = Section.SIMULATION })
                Section.SIMULATION -> SimulationScreen(viewModel)
                Section.SAVED -> SavedRoutesScreen(viewModel, onLoaded = { section = Section.ROUTE })
                Section.MORE -> MoreScreen(viewModel)
            }
        }
    }
}
