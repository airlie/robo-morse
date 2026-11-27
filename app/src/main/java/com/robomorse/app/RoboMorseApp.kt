package com.robomorse.app

import android.app.Application
import com.robomorse.app.audio.MorsePlayer
import com.robomorse.app.data.MorseDatabase
import com.robomorse.app.data.Repository
import com.robomorse.app.data.SettingsRepository
import com.robomorse.app.reminder.Reminders

/** Simple manual dependency container. */
class AppContainer(app: Application) {
    val settings = SettingsRepository(app)
    val repository = Repository(MorseDatabase.create(app).dao(), settings)
    val player = MorsePlayer()
    val sidetone = com.robomorse.app.audio.Sidetone(app)
    val voice = com.robomorse.app.audio.VoiceListener(app)

    /** Hand-off from the practice screen to the summary screen. */
    var lastResult: PracticeResult? = null
}

data class PracticeResult(
    val summary: com.robomorse.core.SessionSummary,
    val save: com.robomorse.app.data.SaveResult,
    val mode: com.robomorse.core.PracticeMode,
    val durationMs: Long,
    val learnedCount: Int,
    val fatigue: Boolean,
)

class RoboMorseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Reminders.createChannel(this)
    }
}
