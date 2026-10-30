package org.koitharu.kotatsu.alternatives.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.ActivityFixReportBinding
import org.koitharu.kotatsu.databinding.ItemFixReportBinding
import java.io.File

/**
 * Shows the latest batch auto-fix report (written by [AutoFixService]):
 * covers, per-item status and the reason, tap a row to open the manga.
 * Copy/share keep the plain-text format for easy pasting back for debugging.
 */
class FixReportActivity : BaseActivity<ActivityFixReportBinding>() {

	private lateinit var fullText: String

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityFixReportBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(true, showUpAsClose = false)
		viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)
		val adapter = ReportAdapter { item -> onReportItemClick(item) }
		viewBinding.recyclerView.adapter = adapter
		fullText = runCatching { File(cacheDir, REPORT_FILE).readText() }
			.getOrElse { getString(R.string.fix_report_empty) }
		val report = loadReport()
		if (report.stamp.isNotBlank()) {
			viewBinding.toolbar.subtitle = getString(R.string.fix_report_generated_at, report.stamp)
		}
		adapter.submit(report.items)
		bindSummary(report.items)
		viewBinding.buttonCopy.setOnClickListener { copyReport() }
		viewBinding.buttonShare.setOnClickListener { shareReport() }
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat,
	): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.root.updatePadding(top = barsInsets.top)
		viewBinding.dockedToolbarChild.updateLayoutParams<MarginLayoutParams> {
			leftMargin = barsInsets.left
			rightMargin = barsInsets.right
			bottomMargin = barsInsets.bottom
		}
		val pad = resources.getDimensionPixelSize(R.dimen.screen_padding)
		viewBinding.summaryContainer.updatePadding(
			left = barsInsets.left + pad,
			right = barsInsets.right + pad,
		)
		viewBinding.recyclerView.updatePadding(
			left = barsInsets.left + pad,
			right = barsInsets.right + pad,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	private fun loadReport(): LoadedReport {
		runCatching {
			val json = File(cacheDir, REPORT_JSON_FILE).readText()
			val data = Json { ignoreUnknownKeys = true }.decodeFromString(FixReportItem.FileDto.serializer(), json)
			val items = data.items.map { FixReportItem.fromDto(it) }
			if (items.isNotEmpty()) {
				return LoadedReport(data.stamp, items)
			}
		}.onFailure { it.printStackTraceDebug() }
		val items = parseTextReport(fullText)
		if (items.isEmpty() && fullText.isNotBlank()) {
			// Report in an unexpected format: show it as-is, one row per line.
			items += fullText.lineSequence()
				.filter { it.isNotBlank() }
				.map { FixReportItem(0L, it.trim(), "", "", null, FixReportItem.Status.NO_FIX_REQUIRED, "") }
		}
		return LoadedReport("", items)
	}

	private fun bindSummary(items: List<FixReportItem>) {
		viewBinding.summaryContainer.isGone = items.isEmpty()
		if (items.isEmpty()) {
			return
		}
		val fixed = items.count { it.status == FixReportItem.Status.FIXED }
		val failed = items.count {
			it.status == FixReportItem.Status.NO_ALTERNATIVES || it.status == FixReportItem.Status.ERROR
		}
		val skipped = items.count { it.status == FixReportItem.Status.NO_FIX_REQUIRED }
		viewBinding.textViewFixed.text = getString(R.string.fix_report_fixed, fixed)
		viewBinding.textViewFailed.text = getString(R.string.fix_report_failed, failed)
		viewBinding.textViewSkipped.text = getString(R.string.fix_report_skipped, skipped)
	}

	private fun copyReport() {
		val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
		clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.fix_report), fullText))
		Toast.makeText(this, R.string.fix_report_copied, Toast.LENGTH_SHORT).show()
	}

	private fun shareReport() {
		val send = Intent(Intent.ACTION_SEND)
			.setType("text/plain")
			.putExtra(Intent.EXTRA_TEXT, fullText)
		startActivity(Intent.createChooser(send, null))
	}

	private fun onReportItemClick(item: FixReportItem) {
		val id = if (item.newId != 0L) item.newId else item.seedId
		if (id != 0L) {
			router.openDetails(id)
		}
	}

	private class LoadedReport(val stamp: String, val items: List<FixReportItem>)

	private class ReportAdapter(
		private val onItemClickListener: (FixReportItem) -> Unit,
	) : RecyclerView.Adapter<ReportAdapter.Holder>() {

		private val items = ArrayList<FixReportItem>()
		private var colorFixed = 0
		private var colorFailed = 0
		private var colorNeutral = 0

		fun submit(newItems: List<FixReportItem>) {
			items.clear()
			items.addAll(newItems)
			notifyDataSetChanged()
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
			val binding = ItemFixReportBinding.inflate(LayoutInflater.from(parent.context), parent, false)
			if (colorFixed == 0) {
				colorFixed = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
				colorFailed = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorError)
				colorNeutral = MaterialColors.getColor(binding.root, android.R.attr.textColorSecondary)
			}
			return Holder(binding)
		}

		override fun getItemCount(): Int = items.size

		override fun onBindViewHolder(holder: Holder, position: Int) {
			val item = items[position]
			with(holder.binding) {
				textViewTitle.text = item.title.ifBlank {
					root.context.getString(R.string.fix_report_empty)
				}
				textViewStatus.text = when (item.status) {
					FixReportItem.Status.FIXED -> root.context.getString(R.string.fix_status_fixed)
					FixReportItem.Status.NO_FIX_REQUIRED -> root.context.getString(R.string.fix_status_skipped)
					FixReportItem.Status.NO_ALTERNATIVES -> root.context.getString(R.string.fix_status_failed)
					FixReportItem.Status.ERROR -> root.context.getString(R.string.fix_status_error)
				}
				textViewStatus.setTextColor(
					when (item.status) {
						FixReportItem.Status.FIXED -> colorFixed
						FixReportItem.Status.NO_FIX_REQUIRED -> colorNeutral
						FixReportItem.Status.NO_ALTERNATIVES, FixReportItem.Status.ERROR -> colorFailed
					},
				)
				textViewDetail.text = item.detail
				textViewDetail.isGone = item.detail.isBlank()
				imageViewCover.setImageAsync(
					item.newCoverUrl ?: item.coverUrl,
					null,
				)
				root.setOnClickListener {
					onItemClickListener(item)
				}
			}
		}

		class Holder(val binding: ItemFixReportBinding) : RecyclerView.ViewHolder(binding.root)
	}

	companion object {

		const val REPORT_FILE = "autofix-report.txt"
		const val REPORT_JSON_FILE = "autofix-report.json"

		private const val NO_ALTERNATIVES_MARK = "no alternatives found"

		// Matches the report lines written by AutoFixService.saveReport():
		// FIXED "title" (source) -> "new title (new source)"
		// SKIP "title" (source)
		// FAIL "title" (source) — reason
		private val LINE_REGEX =
			Regex("""^(FIXED|SKIP|FAIL)\s+"(.*?)"\s+\(([^)]*)\)(?:\s+->\s+"(.*)")?(?:\s+—\s+(.*))?$""")

		private fun parseTextReport(text: String): ArrayList<FixReportItem> {
			val items = ArrayList<FixReportItem>()
			text.lineSequence().forEach { line ->
				val match = LINE_REGEX.find(line.trim()) ?: return@forEach
				val (marker, title, source, target, reason) = match.destructured
				items += when (marker) {
					"FIXED" -> FixReportItem(0L, title, source, "", null, FixReportItem.Status.FIXED, target)
					"SKIP" -> FixReportItem(0L, title, source, "", null, FixReportItem.Status.NO_FIX_REQUIRED, "")
					else -> {
						val status = if (reason.equals(NO_ALTERNATIVES_MARK, ignoreCase = true)) {
							FixReportItem.Status.NO_ALTERNATIVES
						} else {
							FixReportItem.Status.ERROR
						}
						FixReportItem(0L, title, source, "", null, status, reason)
					}
				}
			}
			return items
		}
	}
}
