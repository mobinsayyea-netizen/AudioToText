package app.audiototext

import android.os.Handler
import android.os.Looper

/** What the screen shows. The service writes it, the activity listens. */
data class Snapshot(
    val running: Boolean,
    val status: String,
    val percent: Int,
    val lines: String
)

object Bus {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var last = Snapshot(false, "Ready. Choose your files, enter the API key, then press Start.", 0, "")

    @Volatile
    var listener: ((Snapshot) -> Unit)? = null

    fun post(s: Snapshot) {
        last = s
        main.post { listener?.invoke(s) }
    }
}
