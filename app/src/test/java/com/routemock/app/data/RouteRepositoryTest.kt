package com.routemock.app.data

import com.routemock.app.domain.GeoPoint
import com.routemock.app.domain.PolylineCodec
import com.routemock.app.domain.RoadRoute
import com.routemock.app.domain.RoadRouteSelection
import com.routemock.app.domain.RouteDraft
import com.routemock.app.domain.RouteMode
import com.routemock.app.domain.RouteValidator
import com.routemock.app.domain.SpeedUnit
import com.routemock.app.domain.StationaryRoute
import com.routemock.app.domain.StraightLineRoute
import com.routemock.app.domain.TrafficBasis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RouteRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private val draft = RouteDraft(
        mode = RouteMode.STRAIGHT_LINE,
        startLatitude = "1.5",
        startLongitude = "-2.5",
        destinationLatitude = "3.5",
        destinationLongitude = "4.5",
        speed = "30",
        speedUnit = SpeedUnit.MPH,
    )
    private val file: File get() = File(folder.root, "routes.json")

    private fun repository() = RouteRepository(file, Dispatchers.Unconfined)

    @Test fun `a saved route survives reloading from disk`() = runBlocking {
        val saved = repository().also { it.load() }.add("Commute", draft)
        val reloaded = repository().also { it.load() }
        assertEquals(listOf(saved), reloaded.routes.value)
        assertEquals(draft, reloaded.routes.value.single().draft)
    }

    @Test fun `no file means an empty list`() = runBlocking {
        val repository = repository().also { it.load() }
        assertEquals(emptyList<SavedRoute>(), repository.routes.value)
        assertFalse(repository.recoveredFromCorruption.value)
    }

    @Test fun `rename changes only the target and persists`() = runBlocking {
        val repository = repository().also { it.load() }
        val first = repository.add("One", draft)
        val second = repository.add("Two", draft)
        repository.rename(first.id, "  Renamed  ")
        val reloaded = repository().also { it.load() }.routes.value
        assertEquals(listOf("Renamed", "Two"), reloaded.map { it.name })
        assertEquals(second.id, reloaded[1].id)
    }

    @Test fun `delete removes only the target and persists`() = runBlocking {
        val repository = repository().also { it.load() }
        val first = repository.add("One", draft)
        repository.add("Two", draft)
        repository.delete(first.id)
        assertEquals(listOf("Two"), repository().also { it.load() }.routes.value.map { it.name })
    }

    @Test fun `routes may share a name because ids identify them`() = runBlocking {
        val repository = repository().also { it.load() }
        repository.add("Same", draft)
        repository.add("Same", draft)
        assertEquals(2, repository.routes.value.map { it.id }.distinct().size)
    }

    @Test fun `blank and overlong names are refused`() {
        val repository = repository()
        val tooLong = "x".repeat(RouteRepository.MAX_NAME_LENGTH + 1)
        listOf("", "   ", tooLong).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.add(name, draft) }
            }
        }
        assertEquals(emptyList<SavedRoute>(), repository.routes.value)
    }

    @Test fun `a corrupt file is set aside rather than overwritten`() = runBlocking {
        file.writeText("{not json")
        val repository = repository().also { it.load() }
        assertTrue(repository.recoveredFromCorruption.value)
        assertEquals(emptyList<SavedRoute>(), repository.routes.value)
        assertEquals("{not json", File(file.path + ".corrupt").readText())
        repository.add("Fresh", draft)
        assertEquals(1, repository().also { it.load() }.routes.value.size)
    }

    @Test fun `a failed write leaves memory and disk as they were`() = runBlocking {
        val repository = repository().also { it.load() }
        repository.add("Kept", draft)
        val before = file.readText()
        File(file.path + ".tmp").mkdir() // Writing the temporary file now fails.
        var failed = false
        try {
            repository.add("Lost", draft)
        } catch (_: IOException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(listOf("Kept"), repository.routes.value.map { it.name })
        assertEquals(before, file.readText())
    }

    @Test fun `a route saved before road mode existed still loads as a straight line`() = runBlocking {
        // Literal old format. `mode` is absent because it was the default when written.
        file.writeText(
            """{"routes":[{"id":"old","name":"Old route","draft":{"startLatitude":"1","startLongitude":"2",""" +
                """"destinationLatitude":"3","destinationLongitude":"4"}}]}""",
        )
        val route = repository().also { it.load() }.routes.value.single()
        assertEquals(RouteMode.STRAIGHT_LINE, route.draft.mode)
        assertEquals(null, route.draft.roadRoute)
        assertTrue(RouteValidator.validate(route.draft).result!!.route is StraightLineRoute)
    }

    @Test fun `an old stationary route still loads as stationary`() = runBlocking {
        file.writeText(
            """{"routes":[{"id":"old","name":"Old","draft":{"mode":"STATIONARY","startLatitude":"1","startLongitude":"2"}}]}""",
        )
        val route = repository().also { it.load() }.routes.value.single()
        assertTrue(RouteValidator.validate(route.draft).result!!.route is StationaryRoute)
    }

    @Test fun `a road route keeps its exact geometry and metadata when saved`() = runBlocking {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01), GeoPoint(0.01, 0.01))
        val selection = RoadRouteSelection(
            encodedShape = PolylineCodec.encode(points),
            estimatedSeconds = 321.0,
            trafficBasis = TrafficBasis.ROAD_SPEED_MODEL,
            providerName = "Test",
            attribution = "Attribution",
            requestedStart = points.first(),
            requestedDestination = points.last(),
            calculatedAtMillis = 42L,
        )
        val road = draft.copy(
            mode = RouteMode.ROAD,
            startLatitude = "0",
            startLongitude = "0",
            destinationLatitude = "0.01",
            destinationLongitude = "0.01",
            roadRoute = selection,
        )
        repository().also { it.load() }.add("Road", road)
        val loaded = repository().also { it.load() }.routes.value.single().draft
        assertEquals(road, loaded)
        assertEquals(RoadRoute(points), RouteValidator.validate(loaded).result!!.route)
    }

    @Test fun `unknown fields from a newer version are tolerated`() = runBlocking {
        file.writeText("""{"version":2,"future":true,"routes":[]}""")
        val repository = repository().also { it.load() }
        assertFalse(repository.recoveredFromCorruption.value)
    }
}
