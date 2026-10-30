package org.koitharu.kotatsu.alternatives.ui

/**
 * One line of the batch auto-fix report: what was attempted and how it ended.
 */
data class FixReportItem(
	val title: String,
	val source: String,
	val status: Status,
	val detail: String,
) {

	enum class Status { FIXED, NO_FIX_REQUIRED, NO_ALTERNATIVES, ERROR }
}
