package site.dayspace.phoneagent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private lateinit var serviceStatus: TextView
    private lateinit var keyStatus: TextView
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var stepsInput: EditText
    private lateinit var taskInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var logView: TextView

    private val dp by lazy { resources.displayMetrics.density }

    companion object {
        private const val REQ_SPEECH = 7
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        AgentRunner.onLog = { text -> logView.text = text }
        AgentRunner.onStateChanged = { refresh() }
        logView.text = AgentRunner.logText()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        AgentRunner.onLog = null
        AgentRunner.onStateChanged = null
    }

    // ---------------------------------------------------------------- UI

    private fun px(v: Int) = (v * dp).toInt()

    private fun section(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, px(18), 0, px(6))
    }

    private fun note(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 13f
        alpha = 0.75f
    }

    private fun button(t: String, onClick: () -> Unit): Button = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(px(18), px(12), px(18), px(24))
        }

        col.addView(TextView(this).apply {
            text = "🤖 Phone Agent"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(note("Claude שולט בטלפון: רואה את המסך, מקיש, גולל ומקליד כדי לבצע משימות."))

        // --- 1. Accessibility
        col.addView(section("1. שירות נגישות"))
        serviceStatus = TextView(this).apply { textSize = 14f }
        col.addView(serviceStatus)
        col.addView(button("פתח הגדרות נגישות") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(note("בהגדרות: אפליקציות שהורדו ← Phone Agent ← הפעלה.\nאם המתג אפור (\"הגדרה מוגבלת\"): לחצו למטה על \"פרטי האפליקציה\" ← ⋮ למעלה ← \"התר הגדרות מוגבלות\", ואז נסו שוב."))
        col.addView(button("פרטי האפליקציה") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })

        // --- 2. API key
        col.addView(section("2. מפתח API של Claude"))
        keyStatus = TextView(this).apply { textSize = 14f }
        col.addView(keyStatus)
        keyInput = EditText(this).apply {
            hint = "sk-ant-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            textDirection = View.TEXT_DIRECTION_LTR
        }
        col.addView(keyInput)
        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keyRow.addView(button("שמור מפתח") { saveKey() })
        keyRow.addView(button("מחק מפתח") {
            SecureStore.clear(this)
            refresh()
        })
        col.addView(keyRow)
        col.addView(note("המפתח נשמר מוצפן במכשיר בלבד ונשלח רק ל-api.anthropic.com."))

        // --- 3. Settings
        col.addView(section("3. הגדרות"))
        col.addView(note("מודל"))
        modelInput = EditText(this).apply {
            setText(prefs.getString("model", DEFAULT_MODEL))
            isSingleLine = true
            textDirection = View.TEXT_DIRECTION_LTR
        }
        col.addView(modelInput)
        col.addView(note("מקסימום צעדים למשימה"))
        stepsInput = EditText(this).apply {
            setText(prefs.getInt("maxSteps", 40).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        }
        col.addView(stepsInput)

        // --- 4. Task
        col.addView(section("4. משימה"))
        taskInput = EditText(this).apply {
            hint = "לדוגמה: תפתח את יוטיוב ותחפש מוזיקה רגועה"
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(prefs.getString("lastTask", ""))
        }
        col.addView(taskInput)
        val runRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = button("▶ הפעל") { startAgent() }
        stopButton = button("⏹ עצור") { AgentRunner.stop() }
        runRow.addView(startButton)
        runRow.addView(stopButton)
        runRow.addView(button("🎤 הקלטה") { listen() })
        col.addView(runRow)

        // --- Log
        val logHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logHeader.addView(section("יומן"))
        logHeader.addView(button("נקה") { AgentRunner.clearLog() })
        col.addView(logHeader)
        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
        }
        col.addView(logView)

        val scroll = ScrollView(this).apply {
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            isFillViewport = true
        }
        // Edge-to-edge (Android 15+): keep content clear of the status bar, nav bar and keyboard.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        return scroll
    }

    private fun refresh() {
        val on = PhoneAgentService.instance != null
        serviceStatus.text = if (on) "✅ השירות פעיל" else "⚪ השירות כבוי – יש להפעיל אותו"
        val key = SecureStore.loadApiKey(this)
        keyStatus.text = if (key != null) "✅ מפתח שמור (…${key.takeLast(4)})" else "⚪ עדיין לא נשמר מפתח"
        startButton.isEnabled = !AgentRunner.running
        stopButton.isEnabled = AgentRunner.running
    }

    private fun saveKey() {
        val k = keyInput.text.toString().trim()
        if (k.length < 20) {
            Toast.makeText(this, "המפתח נראה קצר מדי", Toast.LENGTH_SHORT).show()
            return
        }
        SecureStore.saveApiKey(this, k)
        keyInput.setText("")
        Toast.makeText(this, "המפתח נשמר", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun startAgent() {
        val key = SecureStore.loadApiKey(this)
        if (key == null) {
            Toast.makeText(this, "שמרו קודם מפתח API", Toast.LENGTH_LONG).show()
            return
        }
        val task = taskInput.text.toString().trim()
        if (task.isEmpty()) {
            Toast.makeText(this, "כתבו משימה", Toast.LENGTH_SHORT).show()
            return
        }
        val model = modelInput.text.toString().trim().ifEmpty { DEFAULT_MODEL }
        val steps = stepsInput.text.toString().toIntOrNull()?.coerceIn(1, 200) ?: 40
        prefs.edit()
            .putString("model", model)
            .putInt("maxSteps", steps)
            .putString("lastTask", task)
            .apply()
        val err = AgentRunner.start(task, key, model, steps)
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            if (PhoneAgentService.instance == null) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        refresh()
    }

    // ---------------------------------------------------------------- voice

    @Suppress("DEPRECATION")
    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "מה לעשות?")
        try {
            startActivityForResult(i, REQ_SPEECH)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "זיהוי דיבור לא זמין במכשיר", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            taskInput.setText(text)
            taskInput.setSelection(text.length)
        }
    }
}
