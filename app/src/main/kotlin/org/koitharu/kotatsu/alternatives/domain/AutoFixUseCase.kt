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
		// Search enabled AND disabled sources, like the original Kotatsu fix did: the
		// replacement often lives on a source the user never enabled. A half-dead source
		// can also leave a placeholder title ("Loading…") in the database, so fall back
		// to the alternative titles before searching.
		val fallbackQuery: String = seed.altTitles.firstOrNull { it.isNotBlank() } ?: seed.title
		val query: String = if (seed.title.contains("loading", ignoreCase = true)) fallbackQuery else seed.title
		// Search at the same level the manual alternatives screen does: no details probe,
		// no language gate (a foreign-language replacement still beats a dead source),
		// enabled and disabled sources alike, ranked best-first. Take the first hit fast,
		// then enrich it with details opportunistically before migrating.
		val first = alternativesUseCase(
			seed,
			sourceScope = AlternativeSourceScope.Enabled,
			query = query,
			loadDetails = false,
			sameLanguageOnly = false,
		).concat(
			alternativesUseCase(
				seed,
				sourceScope = AlternativeSourceScope.Disabled,
				query = query,
				loadDetails = false,
				sameLanguageOnly = false,
			),
		).selectLastWithTimeout(0, 40, TimeUnit.SECONDS)
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
