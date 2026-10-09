package app.audiototext

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private val picked = ArrayList<Uri>()
    private val pickedNames = ArrayList<String>()

    private lateinit var keyEt: EditText
    private lateinit var rememberCb: CheckBox
    private lateinit var modelEt: EditText
    private lateinit var pickedTv: TextView
    private lateinit var chunkEt: EditText
    private lateinit var gapEt: EditText
    private lateinit var langSp: Spinner
    private lateinit var autoCb: CheckBox
    private lateinit var soundCb: CheckBox
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var bar: ProgressBar
    private lateinit var listTv: TextView

    private val langValues = listOf("hi", "hiroman", "en", "auto")
    private val langNames = listOf(
        "Spoken Hindi in Devanagari, exactly as spoken",
        "Spoken Hindi in English letters (Hinglish)",
        "English",
        "Detect the language automatically"
    )
    private val sp by lazy { getSharedPreferences("att", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadPrefs()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        Bus.listener = { render(it) }
        render(Bus.last)
    }

    override fun onPause() {
        Bus.listener = null
        super.onPause()
    }

    // ------------------------------------------------------------------ UI

    private fun label(text: String, forView: View? = null): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 16f
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(0, dp(14), 0, dp(4))
        if (forView != null) {
            if (forView.id == View.NO_ID) forView.id = View.generateViewId()
            t.labelFor = forView.id
        }
        return t
    }

    private fun edit(hint: String, type: Int): EditText {
        val e = EditText(this)
        e.hint = hint
        e.textSize = 17f
        e.setSingleLine()
        e.inputType = type
        e.minimumHeight = dp(52)
        return e
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 17f
        b.isAllCaps = false
        b.minimumHeight = dp(56)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun check(text: String): CheckBox {
        val c = CheckBox(this)
        c.text = text
        c.textSize = 16f
        c.minimumHeight = dp(48)
        return c
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(12), dp(16), dp(28))
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        val head = TextView(this)
        head.text = "Audio to Text"
        head.textSize = 26f
        head.setTypeface(null, Typeface.BOLD)
        head.isAccessibilityHeading = true
        root.addView(head)

        keyEt = edit("Paste your Gemini API key", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        root.addView(label("Gemini API key", keyEt))
        root.addView(keyEt)
        rememberCb = check("Remember the key on this phone")
        root.addView(rememberCb)

        modelEt = edit("Model name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        root.addView(label("Model", modelEt))
        root.addView(modelEt)
        root.addView(button("Show models available for my key") { showModels() })

        root.addView(label("Audio files"))
        root.addView(button("Choose audio files or a ZIP") { pickFiles() })
        pickedTv = TextView(this)
        pickedTv.textSize = 16f
        pickedTv.text = "Nothing selected yet."
        root.addView(pickedTv)

        chunkEt = edit("Minutes", InputType.TYPE_CLASS_NUMBER)
        root.addView(label("Minutes per piece (2 to 20)", chunkEt))
        root.addView(chunkEt)
        gapEt = edit("Seconds", InputType.TYPE_CLASS_NUMBER)
        root.addView(label("Seconds to wait between requests (0 to 120)", gapEt))
        root.addView(gapEt)

        langSp = Spinner(this)
        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, langNames)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        langSp.adapter = ad
        langSp.minimumHeight = dp(52)
        root.addView(label("Language of the transcript", langSp))
        root.addView(langSp)

        autoCb = check("When everything is finished, save the .txt file to Downloads automatically")
        root.addView(autoCb)
        soundCb = check("Play a sound as each part and each file finishes")
        soundCb.setOnClickListener { sp.edit().putBoolean("sound", soundCb.isChecked).apply() }
        root.addView(soundCb)

        startBtn = button("Start") { onStart() }
        root.addView(startBtn)
        stopBtn = button("Stop") { startService(Intent(this, TranscribeService::class.java).setAction(TranscribeService.ACTION_STOP)) }
        root.addView(stopBtn)
        saveBtn = button("Save text file to Downloads") { onSave() }
        root.addView(saveBtn)
        root.addView(button("Allow background running (battery setting)") { askBattery() })
        root.addView(button("Clear saved progress") { onClear() })

        root.addView(label("Status"))
        statusTv = TextView(this)
        statusTv.textSize = 18f
        statusTv.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        root.addView(statusTv)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        root.addView(bar)

        root.addView(label("Files"))
        listTv = TextView(this)
        listTv.textSize = 16f
        listTv.setTextIsSelectable(true)
        root.addView(listTv)
    }

    private fun render(s: Snapshot) {
        statusTv.text = s.status
        bar.progress = s.percent
        listTv.text = s.lines
        startBtn.isEnabled = !s.running
        stopBtn.isEnabled = s.running
        saveBtn.isEnabled = File(filesDir, "combined.txt").exists()
        soundCb.isChecked = sp.getBoolean("sound", true)
    }

    // ------------------------------------------------------------------ settings

    private fun loadPrefs() {
        val remembered = sp.getBoolean("remember", false)
        rememberCb.isChecked = remembered
        if (remembered) keyEt.setText(sp.getString("key", ""))
        modelEt.setText(sp.getString("model", "gemini-3.5-flash-lite"))
        chunkEt.setText(sp.getInt("chunk", 8).toString())
        gapEt.setText(sp.getInt("gap", 8).toString())
        langSp.setSelection(langValues.indexOf(sp.getString("lang", "hi")).coerceAtLeast(0))
        autoCb.isChecked = sp.getBoolean("autodl", true)
        soundCb.isChecked = sp.getBoolean("sound", true)
    }

    private fun savePrefs(chunk: Int, gap: Int) {
        val e = sp.edit()
        e.putBoolean("remember", rememberCb.isChecked)
        if (rememberCb.isChecked) e.putString("key", keyEt.text.toString().trim()) else e.remove("key")
        e.putString("model", modelEt.text.toString().trim())
        e.putInt("chunk", chunk)
        e.putInt("gap", gap)
        e.putString("lang", langValues[langSp.selectedItemPosition])
        e.putBoolean("autodl", autoCb.isChecked)
        e.putBoolean("sound", soundCb.isChecked)
        e.apply()
    }

    // ------------------------------------------------------------------ actions

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("audio/*", "video/mp4", "application/zip", "application/x-zip-compressed", "application/octet-stream")
        )
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else if (data.data != null) {
            uris.add(data.data!!)
        }
        picked.clear()
        pickedNames.clear()
        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // not every provider allows it
            }
            picked.add(u)
            pickedNames.add(Util.displayName(this, u))
        }
        val shown = pickedNames.take(5).joinToString(", ")
        val more = if (pickedNames.size > 5) " and ${pickedNames.size - 5} more" else ""
        pickedTv.text = "${picked.size} selected: $shown$more"
        pickedTv.announceForAccessibility(pickedTv.text)
    }

    private fun onStart() {
        val key = keyEt.text.toString().trim()
        val model = modelEt.text.toString().trim()
        if (key.isEmpty()) { toast("Enter your Gemini API key first."); return }
        if (model.isEmpty()) { toast("Enter a model name first."); return }
        if (picked.isEmpty()) { toast("Choose audio files or a ZIP first."); return }
        val chunk = (chunkEt.text.toString().toIntOrNull() ?: 8).coerceIn(2, 20)
        val gap = (gapEt.text.toString().toIntOrNull() ?: 8).coerceIn(0, 120)
        savePrefs(chunk, gap)
        val i = Intent(this, TranscribeService::class.java)
        i.action = TranscribeService.ACTION_START
        i.putExtra("key", key)
        i.putExtra("model", model)
        i.putExtra("chunk", chunk)
        i.putExtra("gap", gap)
        i.putExtra("lang", langValues[langSp.selectedItemPosition])
        i.putExtra("autodl", autoCb.isChecked)
        i.putStringArrayListExtra("uris", ArrayList(picked.map { it.toString() }))
        startForegroundService(i)
        if (!batteryOk() && !sp.getBoolean("asked_battery", false)) {
            sp.edit().putBoolean("asked_battery", true).apply()
            askBattery()
        }
    }

    private fun onSave() {
        val f = File(filesDir, "combined.txt")
        if (!f.exists()) { toast("There is no finished text yet."); return }
        try {
            val name = Util.saveToDownloads(this, f.readText())
            toast("Saved $name in Downloads.")
        } catch (e: Exception) {
            toast("Could not save: ${e.message}")
        }
    }

    private fun onClear() {
        if (TranscribeService.running) { toast("Press Stop first."); return }
        File(filesDir, "parts").deleteRecursively()
        File(filesDir, "combined.txt").delete()
        Bus.post(Snapshot(false, "Saved progress cleared.", 0, ""))
    }

    private fun batteryOk(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast("Open Settings, Battery, and set this app to Unrestricted.")
            }
        }
    }

    private fun showModels() {
        val key = keyEt.text.toString().trim()
        if (key.isEmpty()) { toast("Enter your Gemini API key first."); return }
        toast("Loading models")
        Thread {
            try {
                val names = Gemini.listModels(key)
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Pick a model")
                        .setItems(names.toTypedArray()) { _, which -> modelEt.setText(names[which]) }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Could not load models: ${e.message}") }
            }
        }.start()
    }
}
