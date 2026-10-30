package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.concat
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class AutoFixUseCase @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val alternativesUseCase: AlternativesUseCase,
	private val migrateUseCase: MigrateUseCase,
	private val mangaDataRepository: MangaDataRepository,
) {

	suspend operator fun invoke(mangaId: Long): Pair<Manga, Manga?> {
		val seed = checkNotNull(
			mangaDataRepository.findMangaById(mangaId, withChapters = true),
		) { "Manga $mangaId not found" }.getDetailsSafe()
		if (seed.isHealthy()) {
			return seed to null // no fix required
		}
		// Candidate queries: the stored title, unless it is a broken placeholder
		// ("Loading..."), in which case the alternative titles lead. A title search that
		// finds nothing gets one retry per alternative title - short or source-specific
		// names ("Overgeared New") often only match under another title.
		// The NSFW content filter is ignored here on purpose: a dead entry is worse than
		// a hit on an adult-flagged source, and the alternatives often live there.
		val altTitle = seed.altTitles.firstOrNull { it.isNotBlank() }
		val primaryTitle = if (seed.title.contains("loading", ignoreCase = true)) altTitle ?: seed.title else seed.title
		val queries = buildList {
			add(primaryTitle)
			if (altTitle != null && altTitle != primaryTitle) add(altTitle)
		}.distinct()
		suspend fun searchFirst(q: String, timeoutSec: Long): Manga? = alternativesUseCase(
			seed,
			sourceScope = AlternativeSourceScope.Enabled,
			query = q,
			loadDetails = false,
			sameLanguageOnly = false,
			ignoreNsfwFilter = true,
		).concat(
			alternativesUseCase(
				seed,
				sourceScope = AlternativeSourceScope.Disabled,
				query = q,
				loadDetails = false,
				sameLanguageOnly = false,
				ignoreNsfwFilter = true,
			),
		).selectLastWithTimeout(0, timeoutSec, TimeUnit.SECONDS)
		var first: Manga? = null
		for ((i, q) in queries.withIndex()) {
			first = searchFirst(q, if (i == 0) 90L else 30L)
			if (first != null) break
		}
		val replacement = first ?: throw NoAlternativesException(ParcelableManga(seed))
		val enriched = runCatchingCancellable {
			mangaRepositoryFactory.create(replacement.source).getDetails(replacement)
		}.getOrNull() ?: replacement
		migrateUseCase(seed, enriched)
		return seed to enriched
	}

	private suspend fun Manga.isHealthy(): Boolean = runCatchingCancellable {
		val repo = mangaRepositoryFactory.create(source)
		val details = if (this.chapters != null) this else repo.getDetails(this)
		val firstChapter = details.chapters?.firstOrNull() ?: return@runCatchingCancellable false
		val pageUrl = repo.getPageUrl(repo.getPages(firstChapter).first())
		pageUrl.toHttpUrlOrNull() != null
	}.getOrDefault(false)

	private suspend fun Manga.getDetailsSafe() = runCatchingCancellable {
		mangaRepositoryFactory.create(source).getDetails(this)
	}.getOrDefault(this)

	@Suppress("UNCHECKED_CAST", "OPT_IN_USAGE")
	private suspend fun <T> Flow<T>.selectLastWithTimeout(
		minCount: Int,
		timeout: Long,
		timeUnit: TimeUnit
	): T? = channelFlow<T?> {
		var lastValue: T? = null
		launch {
			delay(timeUnit.toMillis(timeout))
			close(InternalTimeoutException(lastValue))
		}
		withIndex().transformWhile { (index, value) ->
			lastValue = value
			emit(value)
			index < minCount && !isClosedForSend
		}.collect {
			send(it)
		}
	}.catch { e ->
		if (e is InternalTimeoutException) {
			emit(e.value as T?)
		} else {
			throw e
		}
	}.lastOrNull()

	class NoAlternativesException(val seed: ParcelableManga) : NoSuchElementException()

	private class InternalTimeoutException(val value: Any?) : CancellationException()
}
