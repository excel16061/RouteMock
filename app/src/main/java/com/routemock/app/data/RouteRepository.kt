package com.routemock.app.data

import com.routemock.app.domain.RouteDraft
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class SavedRoute(val id: String, val name: String, val draft: RouteDraft)

@Serializable
private data class RouteLibrary(val version: Int = 1, val routes: List<SavedRoute> = emptyList())

/** Stores routes in one private JSON file, replaced atomically on every change. */
class RouteRepository(private val file: File, private val dispatcher: CoroutineDispatcher) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val mutableRoutes = MutableStateFlow<List<SavedRoute>>(emptyList())
    private val mutableRecoveredFromCorruption = MutableStateFlow(false)

    val routes: StateFlow<List<SavedRoute>> = mutableRoutes.asStateFlow()

    /** The file was unreadable, so it was set aside instead of being overwritten. */
    val recoveredFromCorruption: StateFlow<Boolean> = mutableRecoveredFromCorruption.asStateFlow()

    suspend fun load() = locked {
        if (!file.exists()) return@locked
        try {
            mutableRoutes.value = json.decodeFromString<RouteLibrary>(file.readText()).routes
        } catch (_: SerializationException) {
            setAsideCorruptFile()
        }
    }

    suspend fun add(name: String, draft: RouteDraft): SavedRoute = locked {
        val route = SavedRoute(UUID.randomUUID().toString(), cleanName(name), draft)
        commit(mutableRoutes.value + route)
        route
    }

    suspend fun rename(id: String, name: String) = locked {
        val cleaned = cleanName(name)
        commit(mutableRoutes.value.map { if (it.id == id) it.copy(name = cleaned) else it })
    }

    suspend fun delete(id: String) = locked {
        commit(mutableRoutes.value.filterNot { it.id == id })
    }

    /** Memory only changes after the write succeeds, so a failed save loses nothing. */
    private fun commit(routes: List<SavedRoute>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.writeText(json.encodeToString(RouteLibrary(routes = routes)))
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        mutableRoutes.value = routes
    }

    private fun setAsideCorruptFile() {
        val destination = File(file.path + ".corrupt")
        if (!file.renameTo(destination)) throw IOException("Could not set aside $file")
        mutableRecoveredFromCorruption.value = true
    }

    private fun cleanName(name: String): String {
        require(isValidName(name)) { "Route name must be 1 to $MAX_NAME_LENGTH characters" }
        return name.trim()
    }

    private suspend fun <T> locked(block: () -> T): T =
        withContext(dispatcher) { mutex.withLock { block() } }

    companion object {
        const val MAX_NAME_LENGTH = 60

        fun isValidName(name: String): Boolean = name.trim().length in 1..MAX_NAME_LENGTH
    }
}
