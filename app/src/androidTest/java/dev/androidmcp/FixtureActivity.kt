package dev.androidmcp

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Test APK only. Never packaged in Phone Link. */
class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 180, 40, 40) }
        val result = TextView(this).apply { text = "Waiting for tap"; textSize = 24f }
        layout.addView(Button(this).apply { text = "Tap target"; isAllCaps = false; setOnClickListener { result.text = "Tapped successfully" } })
        layout.addView(result)
        layout.addView(EditText(this).apply { hint = "Editable target"; setSingleLine() })
        setContentView(layout)
    }
}
