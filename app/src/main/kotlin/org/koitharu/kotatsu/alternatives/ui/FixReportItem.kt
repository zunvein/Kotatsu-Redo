package org.koitharu.kotatsu.alternatives.ui

import kotlinx.serialization.Serializable

/**
 * One entry of the batch auto-fix report: what was attempted and how it ended.
 */
data class FixReportItem(
	val seedId: Long,
	val title: String,
	val source: String,
	val sourceName: String,
	val coverUrl: String?,
	val status: Status,
	val detail: String,
	val newId: Long = 0L,
	val newTitle: String = "",
	val newSource: String = "",
	val newSourceName: String = "",
	val newCoverUrl: String? = null,
) {

	enum class Status { FIXED, NO_FIX_REQUIRED, NO_ALTERNATIVES, ERROR }

	@Serializable
	data class Dto(
		val seedId: Long = 0L,
		val title: String = "",
		val source: String = "",
		val sourceName: String = "",
		val coverUrl: String? = null,
		val status: String,
		val detail: String = "",
		val newId: Long = 0L,
		val newTitle: String = "",
		val newSource: String = "",
		val newSourceName: String = "",
		val newCoverUrl: String? = null,
	)

	@Serializable
	data class FileDto(
		val stamp: String = "",
		val items: List<Dto> = emptyList(),
	)

	fun toDto() = Dto(
		seedId = seedId,
		title = title,
		source = source,
		sourceName = sourceName,
		coverUrl = coverUrl,
		status = status.name,
		detail = detail,
		newId = newId,
		newTitle = newTitle,
		newSource = newSource,
		newSourceName = newSourceName,
		newCoverUrl = newCoverUrl,
	)

	companion object {

		fun fromDto(dto: Dto) = FixReportItem(
			seedId = dto.seedId,
			title = dto.title,
			source = dto.source,
			sourceName = dto.sourceName,
			coverUrl = dto.coverUrl,
			status = runCatching { Status.valueOf(dto.status) }.getOrDefault(Status.ERROR),
			detail = dto.detail,
			newId = dto.newId,
			newTitle = dto.newTitle,
			newSource = dto.newSource,
			newSourceName = dto.newSourceName,
			newCoverUrl = dto.newCoverUrl,
		)
	}
}
