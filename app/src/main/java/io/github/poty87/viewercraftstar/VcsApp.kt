package io.github.poty87.viewercraftstar

import android.app.Application

/** L'app: prima di tutto (schermate e servizi) si legge la lingua scelta. */
class VcsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        L.init(this)
    }
}
