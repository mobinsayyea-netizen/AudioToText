package app.audiototext

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

internal class Item(val label: String, val uri: Uri, val entry: String?)

class TranscribeService : Service() {

    companion object {
        const val ACTION_START = "app.audiototext.START"
        const val ACTION_STOP = "app.audiototext.STOP"
        const val ACTION_SOUND = "app.audiototext.SOUND"
        private const val CHANNEL = "att_progress"
        private const val NOTIF_ID = 1
        private const val DONE_ID = 2
        private val AUDIO_EXT = Regex(".*\\.(mp3|m4a|wav|aac|ogg|oga|opus|flac|mp4|webm|mpeg|mpga|3gp|amr)$", RegexOption.IGNORE_CASE)

        @Volatile
        var stopFlag = false

        @Volatile
        var running = false
    }

    private lateinit var nm: NotificationManager
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var tone: ToneGenerator? = null
    private val prefs by lazy { getSharedPreferences("att", Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transcription progress", NotificationManager.IMPORTANCE_LOW))
        try {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        } catch (e: Exception) {
            tone = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopFlag = true
                if (!running) stopSelf()
            }
            ACTION_SOUND -> {
                prefs.edit().putBoolean("sound", !soundOn()).apply()
                if (running) {
                    val s = Bus.last
                    nm.notify(NOTIF_ID, buildNotif(s.status.substringBefore("\n"), s.status.substringAfter("\n", ""), s.percent))
                } else {
                    stopSelf()
                }
            }
            ACTION_START -> {
                if (running) return START_NOT_STICKY
                running = true
                stopFlag = false
                startForeground(NOTIF_ID, buildNotif("Starting", "Getting ready", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                acquireLocks()
                val startIntent = intent!!
                val uris = startIntent.getStringArrayListExtra("uris")?.map { Uri.parse(it) } ?: emptyList()
                val key = startIntent.getStringExtra("key") ?: ""
                val model = startIntent.getStringExtra("model") ?: ""
                val chunk = startIntent.getIntExtra("chunk", 8)
                val gap = startIntent.getIntExtra("gap", 8)
                val lang = startIntent.getStringExtra("lang") ?: "hi"
                val autoDl = startIntent.getBooleanExtra("autodl", true)
                Thread {
                    try {
                        runJob(uris, key, model, chunk, gap, lang, autoDl)
                    } finally {
                        running = false
                        releaseLocks()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }.start()
            }
        }
        return START_NOT_STICKY
    }

    /** Swiping the app away from Recents (Close all) stops the work. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopFlag = true
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseLocks()
        try {
            tone?.release()
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- locks, sound, notification

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiototext:job").apply { acquire(6 * 60 * 60 * 1000L) }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "audiototext:wifi").apply { acquire() }
        } catch (e: Exception) {
            // keep going without the locks
        }
    }

    private fun releaseLocks() {
        try {
            if (wake?.isHeld == true) wake?.release()
            if (wifi?.isHeld == true) wifi?.release()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun soundOn() = prefs.getBoolean("sound", true)

    private fun beep(type: Int, ms: Int) {
        if (!soundOn()) return
        try {
            tone?.startTone(type, ms)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun buildNotif(title: String, detail: String, percent: Int): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val stopPi = PendingIntent.getService(this, 1, Intent(this, TranscribeService::class.java).setAction(ACTION_STOP), flags)
        val soundPi = PendingIntent.getService(this, 2, Intent(this, TranscribeService::class.java).setAction(ACTION_SOUND), flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("$title  ($percent%)")
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, false)
            .addAction(0, "Stop", stopPi)
            .addAction(0, if (soundOn()) "Sound off" else "Sound on", soundPi)
            .build()
    }

    private fun publish(title: String, detail: String, percent: Int, lines: List<String>) {
        Bus.post(Snapshot(true, "$title\n$detail", percent, lines.joinToString("\n")))
        nm.notify(NOTIF_ID, buildNotif(title, detail, percent))
    }

    private fun finalNotification(title: String, detail: String) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        nm.notify(
            DONE_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(Notification.BigTextStyle().bigText(detail))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    // ---------------------------------------------------------------- the work

    private fun stopped() = stopFlag

    private fun expandItems(uris: List<Uri>): List<Item> {
        val sources = uris.map { it to Util.displayName(this, it) }.sortedWith { a, b -> Util.natural(a.second, b.second) }
        val out = ArrayList<Item>()
        for ((uri, name) in sources) {
            if (name.lowercase().endsWith(".zip")) {
                val entries = ArrayList<String>()
                contentResolver.openInputStream(uri)?.use { ins ->
                    ZipInputStream(ins).use { z ->
                        var e = z.nextEntry
                        while (e != null) {
                            val n = e.name
                            if (!e.isDirectory && !n.startsWith("__MACOSX") && AUDIO_EXT.matches(n) &&
                                !n.substringAfterLast('/').startsWith(".")
                            ) entries.add(n)
                            e = z.nextEntry
                        }
                    }
                }
                entries.sortedWith { a, b -> Util.natural(a, b) }.forEach { out.add(Item(it.substringAfterLast('/'), uri, it)) }
            } else {
                out.add(Item(name, uri, null))
            }
        }
        return out
    }

    private fun copyToCache(item: Item): File {
        val out = File(cacheDir, "current.bin")
        if (out.exists()) out.delete()
        val ins = contentResolver.openInputStream(item.uri) ?: throw IOException("Could not open ${item.label}")
        ins.use { s ->
            if (item.entry == null) {
                out.outputStream().use { s.copyTo(it) }
            } else {
                ZipInputStream(s).use { z ->
                    var e = z.nextEntry
                    while (e != null && e.name != item.entry) e = z.nextEntry
                    if (e == null) throw IOException("File not found inside the ZIP: ${item.entry}")
                    out.outputStream().use { z.copyTo(it) }
                }
            }
        }
        return out
    }

    private fun runJob(uris: List<Uri>, key: String, model: String, chunkMin: Int, gapSec: Int, lang: String, autoDl: Boolean) {
        val lines = ArrayList<String>()
        val results = ArrayList<Pair<String, String>>()
        var aborted = ""
        val partsDir = File(filesDir, "parts").apply { mkdirs() }
        val wavDir = File(cacheDir, "wav")
        try {
            publish("Preparing", "Reading the list of files", 0, lines)
            val items = expandItems(uris)
            if (items.isEmpty()) {
                aborted = "No audio files were found in your selection."
            }
            val n = items.size
            items.forEachIndexed { i, it -> lines.add("${i + 1}. ${it.label}: waiting") }

            for ((idx, item) in items.withIndex()) {
                if (stopped() || aborted.isNotEmpty()) break
                val head = "File ${idx + 1} of $n"
                lines[idx] = "${idx + 1}. ${item.label}: working"
                publish(head, "${item.label}: preparing", (idx * 100) / n, lines)
                var tmp: File? = null
                try {
                    val src = copyToCache(item)
                    tmp = src
                    wavDir.deleteRecursively()
                    val pieces: List<Piece> =
                        if (Audio.isMp3(item.label)) Audio.sliceMp3(src, chunkMin)
                        else Audio.decodeToWavPieces(src, chunkMin, wavDir) { stopped() }
                    if (stopped()) break
                    val total = pieces.size
                    val texts = ArrayList<String>()
                    try {
                        for ((p, piece) in pieces.withIndex()) {
                            if (stopped()) break
                            val percent = ((idx * 100.0 + p * 100.0 / total) / n).toInt()
                            val ck = Util.md5("${item.label}|${src.length()}|$chunkMin|$lang|$p")
                            val cached = File(partsDir, "$ck.txt")
                            val t: String
                            if (cached.exists()) {
                                t = cached.readText()
                            } else {
                                publish("$head, part ${p + 1} of $total", "${item.label}: sending to Gemini", percent, lines)
                                val r = Gemini.transcribe(
                                    key, model, Audio.read(piece), piece.mime,
                                    Gemini.buildPrompt(lang, p + 1, total),
                                    { attempt, sec, why ->
                                        publish("$head, part ${p + 1} of $total", "Retry $attempt in $sec s ($why)", percent, lines)
                                    },
                                    { stopped() }
                                )
                                var text = r.text
                                if (r.finish.isNotEmpty() && r.finish != "STOP") {
                                    text += "\n[Note: Gemini stopped here because of ${r.finish}; this part may be incomplete]"
                                }
                                if (text.isBlank()) text = "[No speech found in this part]"
                                cached.writeText(text)
                                t = text
                                beep(ToneGenerator.TONE_PROP_BEEP, 150)
                                val more = p < total - 1 || idx < n - 1
                                if (more && gapSec > 0 && !stopped()) {
                                    publish("$head, part ${p + 1} of $total", "Done. Waiting $gapSec s before the next request", percent, lines)
                                    Gemini.pause(gapSec) { stopped() }
                                }
                            }
                            texts.add(t)
                        }
                    } catch (g: GeminiException) {
                        if (texts.isNotEmpty()) results.add(item.label to (Util.joinParts(texts) + "\n[Stopped before the end of this file]"))
                        throw g
                    }
                    if (texts.isNotEmpty()) {
                        val note = if (texts.size < total) "\n[Stopped before the end of this file]" else ""
                        results.add(item.label to (Util.joinParts(texts) + note))
                    }
                    if (texts.size == total) {
                        lines[idx] = "${idx + 1}. ${item.label}: done"
                        beep(ToneGenerator.TONE_PROP_ACK, 300)
                    } else {
                        lines[idx] = "${idx + 1}. ${item.label}: stopped"
                    }
                } catch (g: GeminiException) {
                    if (!stopped()) {
                        aborted = g.message ?: "Gemini error"
                        lines[idx] = "${idx + 1}. ${item.label}: stopped (error)"
                    }
                    break
                } catch (e: Exception) {
                    lines[idx] = "${idx + 1}. ${item.label}: failed"
                    results.add(item.label to "[Error: ${e.message}]")
                } finally {
                    tmp?.delete()
                    wavDir.deleteRecursively()
                }
            }
        } catch (t: Throwable) {
            aborted = "Unexpected error: ${t.message}"
        }

        // ---- finish
        val userStopped = stopFlag
        val combined = if (results.isNotEmpty()) Util.buildCombined(results) else ""
        if (combined.isNotEmpty()) {
            try {
                File(filesDir, "combined.txt").writeText(combined)
            } catch (e: Exception) {
                // ignore
            }
        }
        var saveMsg = ""
        if (combined.isNotEmpty() && autoDl && !userStopped) {
            saveMsg = try {
                "Saved " + Util.saveToDownloads(this, combined) + " in Downloads."
            } catch (e: Exception) {
                "Could not save automatically (${e.message}). Press Save text file."
            }
        } else if (combined.isNotEmpty()) {
            saveMsg = "Press Save text file to keep what is finished."
        }
        val title = when {
            aborted.isNotEmpty() -> "Stopped: $aborted"
            userStopped -> "Stopped by you"
            else -> "All done"
        }
        val detail = if (saveMsg.isEmpty()) "Nothing was transcribed." else saveMsg
        Bus.post(Snapshot(false, "$title\n$detail", if (aborted.isEmpty() && !userStopped) 100 else Bus.last.percent, lines.joinToString("\n")))
        finalNotification(title, detail)
        if (aborted.isEmpty() && !userStopped) beep(ToneGenerator.TONE_PROP_ACK, 600)
        else if (aborted.isNotEmpty()) beep(ToneGenerator.TONE_SUP_ERROR, 600)
    }
}
