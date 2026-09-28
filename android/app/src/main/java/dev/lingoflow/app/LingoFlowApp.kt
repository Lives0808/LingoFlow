package dev.lingoflow.app

import android.app.Application
import dev.lingoflow.app.core.translate.SeedDictionary
import dev.lingoflow.app.data.SqliteMemoryStore

class LingoFlowApp : Application() {
    /** Warm the database and dictionary off the main thread on first start. */
    override fun onCreate() {
        super.onCreate()
        Thread {
            runCatching {
                SqliteMemoryStore(this).writableDatabase
                SeedDictionary.entries.size
            }
        }.start()
    }
}
