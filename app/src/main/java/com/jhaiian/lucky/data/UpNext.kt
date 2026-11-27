package com.jhaiian.lucky.data

class ContinueList(val entries: List<WatchEntry>, val upNext: Set<String>, val shows: Map<String, List<VideoItem>>) {
    fun showOf(uri: String, name: String?): List<VideoItem>? = ShowMemory.keyFor(name)?.let { shows[it] }?.takeIf { list -> list.any { it.uri == uri } }
}

private val episodeOrder = compareBy<VideoItem>({ ShowMemory.episodeNumber(it.name) ?: Long.MAX_VALUE }, { it.name.lowercase() })

object UpNext {
    fun build(history: Map<String, WatchEntry>, videos: List<VideoItem>, limit: Int = 20): ContinueList {
        val byUri = videos.associateBy { it.uri }
        val shows = videos
            .mapNotNull { video -> ShowMemory.keyFor(video.name)?.let { it to video } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .mapValues { (_, list) -> list.sortedWith(episodeOrder) }
        val seen = HashSet<String>()
        val result = ArrayList<WatchEntry>()
        val upNext = HashSet<String>()
        for (entry in history.values.sortedByDescending { it.updatedAt }) {
            if (result.size >= limit) break
            val video = byUri[entry.uri]
            val show = ShowMemory.keyFor(video?.name ?: entry.title)?.takeIf { it in shows }
            if (show != null && show in seen) continue
            when {
                entry.resumable -> {
                    result += entry
                    if (show != null) seen += show
                }
                entry.finished && show != null -> {
                    seen += show
                    val episodes = shows.getValue(show)
                    val index = episodes.indexOfFirst { it.uri == entry.uri }
                    if (index < 0) continue
                    val next = episodes.drop(index + 1).firstOrNull { history[it.uri]?.finished != true } ?: continue
                    val saved = history[next.uri]
                    result += WatchEntry(
                        uri = next.uri,
                        title = next.title,
                        positionMs = saved?.takeIf { it.resumable }?.positionMs ?: 0L,
                        durationMs = next.durationMs,
                        updatedAt = entry.updatedAt
                    )
                    upNext += next.uri
                }
            }
        }
        return ContinueList(result, upNext, shows)
    }
}
