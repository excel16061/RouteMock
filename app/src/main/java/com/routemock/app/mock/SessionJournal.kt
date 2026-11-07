package com.routemock.app.mock

import android.content.Context
import androidx.core.content.edit

/**
 * Records on disk that test providers may exist, so a session cut short by a
 * killed process can be detected and cleaned up the next time the app opens.
 */
class SessionJournal(context: Context) {
    private val preferences = context.getSharedPreferences("session_journal", Context.MODE_PRIVATE)

    var providersMayExist: Boolean
        get() = preferences.getBoolean(KEY_PROVIDERS_MAY_EXIST, false)
        set(value) = preferences.edit(commit = true) { putBoolean(KEY_PROVIDERS_MAY_EXIST, value) }

    private companion object {
        const val KEY_PROVIDERS_MAY_EXIST = "providers_may_exist"
    }
}
