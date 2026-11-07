package com.routemock.app.consent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Serializable
enum class ConsentScope { TERMS, PRIVACY, MOCK_LOCATION, DASHBOARD_UPLOAD }

@Serializable
enum class ConsentEventType { ACCEPTED, REPLACED, WITHDRAWN }

/** Who acted. Automated testing must never be recorded as a person's decision. */
@Serializable
enum class ConsentOrigin {
    USER,
    AUTOMATED_TEST,

    /** Recorded before origins existed, so who acted cannot be known. */
    UNKNOWN,
}

/** One document exactly as it was presented, identified by version and content hash. */
@Serializable
data class DocumentRef(val scope: ConsentScope, val version: Int, val sha256: String)

data class ConsentDocument(val scope: ConsentScope, val version: Int, val assetPath: String, val text: String) {
    val ref: DocumentRef get() = DocumentRef(scope, version, sha256(text))
}

@Serializable
data class ConsentEvent(
    val type: ConsentEventType,
    val atUtcMillis: Long,
    val appVersion: String,
    val documents: List<DocumentRef>,
    val origin: ConsentOrigin = ConsentOrigin.UNKNOWN,
)

/** A later note about earlier events. The events themselves are never rewritten. */
@Serializable
data class ConsentCorrection(
    val eventIndexes: List<Int>,
    val origin: ConsentOrigin,
    val reason: String,
    val atUtcMillis: Long,
)

@Serializable
data class ConsentReceipt(
    val schema: Int = 1,
    val events: List<ConsentEvent> = emptyList(),
    val corrections: List<ConsentCorrection> = emptyList(),
) {
    /** The origin to rely on: the latest correction naming an event, or what it recorded. */
    fun effectiveOrigin(eventIndex: Int): ConsentOrigin =
        corrections.lastOrNull { eventIndex in it.eventIndexes }?.origin ?: events[eventIndex].origin
}

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * An append-only record of what was accepted. Only the last event for a scope counts,
 * and only if it matches the current document, so any change to a document's text
 * requires accepting it again. It records a decision: it is not a liability shield.
 */
class ConsentLedger(
    private val file: File,
    private val documents: List<ConsentDocument>,
    private val appVersion: String,
    private val nowMillis: () -> Long,
    private val origin: () -> ConsentOrigin = { ConsentOrigin.USER },
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val mutableReceipt = MutableStateFlow(load())

    val receipt: StateFlow<ConsentReceipt> = mutableReceipt.asStateFlow()

    fun isGranted(scope: ConsentScope, receipt: ConsentReceipt = mutableReceipt.value): Boolean {
        val current = documents.first { it.scope == scope }.ref
        val latest = receipt.events.lastOrNull { event -> event.documents.any { it.scope == scope } } ?: return false
        return latest.type != ConsentEventType.WITHDRAWN && current in latest.documents
    }

    /** Whether the app may be used at all. */
    fun requiredGranted(receipt: ConsentReceipt = mutableReceipt.value): Boolean =
        REQUIRED.all { isGranted(it, receipt) }

    fun accept(scopes: Set<ConsentScope>) {
        val fresh = scopes.filterNot(::isGranted)
        if (fresh.isEmpty()) return
        val hadOlder = fresh.any { scope ->
            mutableReceipt.value.events.lastOrNull { event -> event.documents.any { it.scope == scope } }
                ?.type?.let { it != ConsentEventType.WITHDRAWN } == true
        }
        append(if (hadOlder) ConsentEventType.REPLACED else ConsentEventType.ACCEPTED, fresh)
    }

    fun withdraw(scopes: Set<ConsentScope>) {
        val granted = scopes.filter(::isGranted)
        if (granted.isNotEmpty()) append(ConsentEventType.WITHDRAWN, granted)
    }

    fun exportJson(): String = json.encodeToString(mutableReceipt.value)

    private fun append(type: ConsentEventType, scopes: List<ConsentScope>) {
        val refs = documents.filter { it.scope in scopes }.map { it.ref }
        val event = ConsentEvent(type, nowMillis(), appVersion, refs, origin())
        val updated = mutableReceipt.value.copy(events = mutableReceipt.value.events + event)
        write(updated)
        mutableReceipt.value = updated
    }

    private fun write(receipt: ConsentReceipt) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.writeText(json.encodeToString(receipt))
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** An unreadable receipt is kept aside and treated as no consent, never as consent. */
    private fun load(): ConsentReceipt {
        if (!file.exists()) return ConsentReceipt()
        return try {
            val stored = json.decodeFromString<ConsentReceipt>(file.readText())
            val corrected = stored.copy(
                events = stored.events.map { event ->
                    if (event.appVersion in VERSIONS_WITHOUT_ORIGIN) event.copy(origin = ConsentOrigin.UNKNOWN) else event
                },
            )
            if (corrected != stored) write(corrected)
            corrected
        } catch (_: SerializationException) {
            file.renameTo(File(file.path + ".corrupt"))
            ConsentReceipt()
        } catch (_: IllegalArgumentException) {
            file.renameTo(File(file.path + ".corrupt"))
            ConsentReceipt()
        }
    }

    companion object {
        val REQUIRED = setOf(ConsentScope.TERMS, ConsentScope.PRIVACY, ConsentScope.MOCK_LOCATION)

        /** These builds wrote no origin, so a stored USER from them cannot be trusted. */
        private val VERSIONS_WITHOUT_ORIGIN = setOf("0.4.0")
    }
}
