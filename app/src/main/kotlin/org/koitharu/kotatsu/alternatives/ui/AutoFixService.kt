package org.koitharu.kotatsu.alternatives.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.AutoFixUseCase
import org.koitharu.kotatsu.alternatives.domain.AutoFixUseCase.NoAlternativesException
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.ui.CoroutineIntentService
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.powerManager
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.withPartialWakeLock
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
class AutoFixService : CoroutineIntentService() {

	@Inject
	lateinit var autoFixUseCase: AutoFixUseCase

	@Inject
	lateinit var mangaDataRepository: MangaDataRepository

	private lateinit var notificationManager: NotificationManagerCompat

	override fun onCreate() {
		super.onCreate()
		notificationManager = NotificationManagerCompat.from(this)
	}

	override suspend fun IntentJobContext.processIntent(intent: Intent) {
		val ids = requireNotNull(intent.getLongArrayExtra(DATA_IDS))
		startForeground(this, ids.size)
		val report = ArrayList<FixReportItem>(ids.size)
		for ((index, mangaId) in ids.withIndex()) {
			powerManager.withPartialWakeLock(TAG) {
				val result = runCatchingCancellable {
					autoFixUseCase.invoke(mangaId)
				}
				report.add(result.toReportItem(mangaId))
			}
			// Live k/N progress on the foreground notification instead of an endless spinner.
			if (checkNotificationPermission(CHANNEL_ID)) {
				notificationManager.notify(
					FOREGROUND_NOTIFICATION_ID,
					buildForegroundNotification(this, index + 1, ids.size),
				)
			}
		}
		val fixed = report.count { it.status == FixReportItem.Status.FIXED }
		val failed = report.count { it.status == FixReportItem.Status.NO_ALTERNATIVES || it.status == FixReportItem.Status.ERROR }
		val skipped = report.count { it.status == FixReportItem.Status.NO_FIX_REQUIRED }
		saveReport(report, fixed, failed, skipped)
		if (checkNotificationPermission(CHANNEL_ID)) {
			notificationManager.notify(TAG, SUMMARY_NOTIFICATION_ID, buildSummaryNotification(fixed, failed, skipped))
		}
	}

	override fun IntentJobContext.onError(error: Throwable) {
		if (checkNotificationPermission(CHANNEL_ID)) {
			notificationManager.notify(TAG, SUMMARY_NOTIFICATION_ID, buildErrorNotification(error))
		}
	}

	private suspend fun Result<Pair<Manga, Manga?>>.toReportItem(mangaId: Long): FixReportItem = fold(
		onSuccess = { (seed, replacement) ->
			if (replacement == null) {
				FixReportItem(
					seedId = seed.id,
					title = seed.title,
					source = seed.source.getTitle(this@AutoFixService),
					sourceName = seed.source.name,
					coverUrl = seed.coverUrl,
					status = FixReportItem.Status.NO_FIX_REQUIRED,
					detail = getString(R.string.fix_reason_no_fix_required),
				)
			} else {
				FixReportItem(
					seedId = seed.id,
					title = seed.title,
					source = seed.source.getTitle(this@AutoFixService),
					sourceName = seed.source.name,
					coverUrl = seed.coverUrl,
					status = FixReportItem.Status.FIXED,
					detail = getString(R.string.fix_reason_migrated, replacement.source.getTitle(this@AutoFixService)),
					newId = replacement.id,
					newTitle = replacement.title,
					newSource = replacement.source.getTitle(this@AutoFixService),
					newSourceName = replacement.source.name,
					newCoverUrl = replacement.coverUrl,
				)
			}
		},
		onFailure = { error ->
			// Generic errors lose the seed inside the use case, which used to make the
			// report row anonymous ("FAIL \"\" ()"). Recover the identity from the stored
			// manga so every failed row still says what was attempted and where.
			val seed = (error as? NoAlternativesException)?.seed?.manga
				?: mangaDataRepository.findMangaById(mangaId, withChapters = false)
			val detail = if (error is NoAlternativesException) {
				getString(R.string.fix_reason_no_alternatives)
			} else {
				error.getDisplayMessage(resources)
			}
			FixReportItem(
				seedId = seed?.id ?: 0L,
				title = seed?.title ?: "",
				source = seed?.source?.getTitle(this@AutoFixService) ?: "",
				sourceName = seed?.source?.name ?: "",
				coverUrl = seed?.coverUrl,
				status = if (error is NoAlternativesException) FixReportItem.Status.NO_ALTERNATIVES else FixReportItem.Status.ERROR,
				detail = detail,
			)
		},
	)

	private fun saveReport(report: List<FixReportItem>, fixed: Int, failed: Int, skipped: Int) {
		runCatching {
			val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
			File(cacheDir, FixReportActivity.REPORT_FILE).writeText(
				buildString {
					appendLine(getString(R.string.fix_report_header, report.size, fixed, failed, skipped))
					appendLine(stamp)
					appendLine()
					for (item in report) {
						when (item.status) {
							FixReportItem.Status.FIXED -> appendLine("FIXED \"${item.title}\" (${item.source}) -> \"${item.newTitle}\" (${item.newSource})")
							FixReportItem.Status.NO_FIX_REQUIRED -> appendLine("SKIP \"${item.title}\" (${item.source})")
							FixReportItem.Status.NO_ALTERNATIVES -> appendLine("FAIL \"${item.title}\" (${item.source}) — no alternatives found")
							FixReportItem.Status.ERROR -> appendLine("FAIL \"${item.title}\" (${item.source}) — ${item.detail}")
						}
					}
				},
			)
			// Structured copy for the report screen (covers, tap-through, per-item reasons).
			File(cacheDir, FixReportActivity.REPORT_JSON_FILE).writeText(
				Json.encodeToString(
					FixReportItem.FileDto(
						stamp = stamp,
						items = report.map { it.toDto() },
					),
				),
			)
		}.onFailure { it.printStackTraceDebug() }
	}

	@SuppressLint("InlinedApi")
	private fun startForeground(jobContext: IntentJobContext, total: Int) {
		val title = getString(R.string.fixing_manga)
		val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_MIN)
			.setName(title)
			.setShowBadge(false)
			.setVibrationEnabled(false)
			.setSound(null, null)
			.setLightsEnabled(false)
			.build()
		notificationManager.createNotificationChannel(channel)

		jobContext.setForeground(
			FOREGROUND_NOTIFICATION_ID,
			buildForegroundNotification(jobContext, 0, total),
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
		)
	}

	private fun buildForegroundNotification(jobContext: IntentJobContext, current: Int, total: Int): Notification {
		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle(getString(R.string.fixing_manga))
			.setContentText(if (total > 1) "$current / $total" else null)
			.setPriority(NotificationCompat.PRIORITY_MIN)
			.setDefaults(0)
			.setSilent(true)
			.setOngoing(true)
			.setProgress(total, current, false)
			.setSmallIcon(R.drawable.ic_stat_auto_fix)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
			.setCategory(NotificationCompat.CATEGORY_PROGRESS)
			.addAction(
				appcompatR.drawable.abc_ic_clear_material,
				getString(android.R.string.cancel),
				jobContext.getCancelIntent(),
			)
			.build()
	}

	private fun buildSummaryNotification(fixed: Int, failed: Int, skipped: Int): Notification {
		val intent = Intent(this, FixReportActivity::class.java)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle(getString(R.string.fix_completed))
			.setContentText(getString(R.string.fix_summary, fixed, failed, skipped))
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setDefaults(0)
			.setSilent(true)
			.setAutoCancel(true)
			.setSmallIcon(R.drawable.ic_stat_done)
			.setContentIntent(
				PendingIntentCompat.getActivity(
					this,
					0,
					intent,
					PendingIntent.FLAG_UPDATE_CURRENT,
					false,
				),
			)
			.build()
	}

	private fun buildErrorNotification(error: Throwable): Notification {
		val message = if (error is NoAlternativesException) {
			getString(R.string.no_alternatives_found, error.seed.manga.title)
		} else {
			error.getDisplayMessage(resources)
		}
		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle(getString(R.string.error_occurred))
			.setContentText(message)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setDefaults(0)
			.setAutoCancel(true)
			.setSmallIcon(android.R.drawable.stat_notify_error)
			.build()
	}

	companion object {

		private const val DATA_IDS = "ids"
		private const val TAG = "auto_fix"
		private const val CHANNEL_ID = "auto_fix"
		private const val FOREGROUND_NOTIFICATION_ID = 38
		private const val SUMMARY_NOTIFICATION_ID = 39

		fun start(context: Context, mangaIds: Collection<Long>): Boolean = try {
			val intent = Intent(context, AutoFixService::class.java)
			intent.putExtra(DATA_IDS, mangaIds.toLongArray())
			ContextCompat.startForegroundService(context, intent)
			true
		} catch (e: Exception) {
			e.printStackTraceDebug()
			false
		}
	}
}
