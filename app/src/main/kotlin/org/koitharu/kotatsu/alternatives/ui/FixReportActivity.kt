package org.koitharu.kotatsu.alternatives.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.koitharu.kotatsu.R
import java.io.File

/**
 * Shows the latest batch auto-fix report (written by [AutoFixService]) with
 * copy/share actions so failed entries are easy to hand back for debugging.
 */
class FixReportActivity : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_fix_report)
		val text = runCatching { File(cacheDir, REPORT_FILE).readText() }
			.getOrElse { getString(R.string.fix_report_empty) }
		findViewById<TextView>(R.id.textView).text = text
		findViewById<Button>(R.id.buttonCopy).setOnClickListener {
			val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
			clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.fix_report), text))
			Toast.makeText(this, R.string.fix_report_copied, Toast.LENGTH_SHORT).show()
		}
		findViewById<Button>(R.id.buttonShare).setOnClickListener {
			val send = Intent(Intent.ACTION_SEND)
				.setType("text/plain")
				.putExtra(Intent.EXTRA_TEXT, text)
			startActivity(Intent.createChooser(send, null))
		}
	}

	companion object {

		const val REPORT_FILE = "autofix-report.txt"
	}
}
