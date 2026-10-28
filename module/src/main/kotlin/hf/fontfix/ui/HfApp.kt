package hf.fontfix.ui

import android.app.Application

class HfApp : Application() {

    override fun onCreate() {
        super.onCreate()
        store.attach()
    }

    companion object {
        val store = SettingsStore()
    }
}
