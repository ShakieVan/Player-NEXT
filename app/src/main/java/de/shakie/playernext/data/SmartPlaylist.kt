package de.shakie.playernext.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator

/**
 * Rule based playlist evaluated against the library and the play statistics.
 * Grouping, comment and BPM are not part of MediaStore and therefore not offered.
 */
data class SmartPlaylist(
    val id: Long,
    val name: String,
    val matchAll: Boolean = true,
    val rules: List<Rule> = emptyList(),
    val limitEnabled: Boolean = false,
    val limitValue: Int = 25,
    val limitUnit: LimitUnit = LimitUnit.ITEMS,
    val selectedBy: SelectedBy = SelectedBy.RANDOM,
    val builtinKey: String? = null,
) {
    enum class Field(val key: String, val kind: Kind) {
        TITLE("title", Kind.TEXT), ALBUM("album", Kind.TEXT), ARTIST("artist", Kind.TEXT),
        ALBUM_ARTIST("album_artist", Kind.TEXT), COMPOSER("composer", Kind.TEXT), GENRE("genre", Kind.TEXT),
        DURATION("duration", Kind.NUMBER), YEAR("year", Kind.NUMBER), DATE_ADDED("date_added", Kind.DATE),
        DATE_MODIFIED("date_modified", Kind.DATE), RATING("rating", Kind.NUMBER), PLAY_COUNT("play_count", Kind.NUMBER),
        SKIP_COUNT("skip_count", Kind.NUMBER), LAST_PLAYED("last_played", Kind.DATE), PATH("path", Kind.TEXT),
        PODCAST("podcast", Kind.BOOL);

        companion object {
            fun from(k: String) = entries.firstOrNull { it.key == k } ?: TITLE
        }
    }

    enum class Kind { TEXT, NUMBER, DATE, BOOL }

    enum class Op(val key: String) {
        CONTAINS("contains"), NOT_CONTAINS("not_contains"), IS("is"), IS_NOT("is_not"),
        STARTS_WITH("starts"), ENDS_WITH("ends"), GREATER("gt"), LESS("lt"),
        AFTER("after"), BEFORE("before"), IN_LAST("in_last"), NOT_IN_LAST("not_in_last"),
        TRUE("true"), FALSE("false");

        companion object {
            fun from(k: String) = entries.firstOrNull { it.key == k } ?: CONTAINS
            fun forKind(kind: Kind) = when (kind) {
                Kind.TEXT -> listOf(CONTAINS, NOT_CONTAINS, IS, IS_NOT, STARTS_WITH, ENDS_WITH)
                Kind.NUMBER -> listOf(IS, IS_NOT, GREATER, LESS)
                Kind.DATE -> listOf(AFTER, BEFORE, IN_LAST, NOT_IN_LAST)
                Kind.BOOL -> listOf(TRUE, FALSE)
            }
        }
    }

    enum class TimeUnit(val key: String, val millis: Long) {
        SECONDS("s", 1000L), MINUTES("min", 60_000L), HOURS("h", 3_600_000L), DAYS("d", 86_400_000L),
        WEEKS("w", 604_800_000L), MONTHS("mo", 2_592_000_000L), YEARS("y", 31_536_000_000L);

        companion object {
            fun from(k: String) = entries.firstOrNull { it.key == k } ?: DAYS
        }
    }

    data class Rule(val field: Field, val op: Op, val value: String, val unit: TimeUnit = TimeUnit.DAYS)

    enum class LimitUnit(val key: String) {
        ITEMS("items"), MINUTES("min"), HOURS("h"), MB("mb"), GB("gb");

        companion object {
            fun from(k: String) = entries.firstOrNull { it.key == k } ?: ITEMS
        }
    }

    enum class SelectedBy(val key: String) {
        RANDOM("random"), TITLE("title"), ALBUM("album"), ARTIST("artist"),
        HIGHEST_RATING("rating_high"), LOWEST_RATING("rating_low"),
        RECENTLY_PLAYED("played_recent"), LEAST_RECENTLY_PLAYED("played_old"),
        MOST_PLAYED("count_high"), LEAST_PLAYED("count_low"),
        RECENTLY_ADDED("added_recent"), LEAST_RECENTLY_ADDED("added_old");

        companion object {
            fun from(k: String) = entries.firstOrNull { it.key == k } ?: RANDOM
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("match", if (matchAll) "all" else "any")
        put("rules", JSONArray().apply {
            rules.forEach { r ->
                put(JSONObject().put("field", r.field.key).put("op", r.op.key).put("value", r.value).put("unit", r.unit.key))
            }
        })
        put("limit", limitEnabled)
        put("limit_value", limitValue)
        put("limit_unit", limitUnit.key)
        put("selected_by", selectedBy.key)
    }.toString()

    fun evaluate(library: LibrarySnapshot, stats: Map<String, SongStats>): List<Song> {
        val now = System.currentTimeMillis()
        var list = library.songs.filter { s ->
            if (rules.isEmpty()) true
            else if (matchAll) rules.all { matches(it, s, stats[s.path], now) }
            else rules.any { matches(it, s, stats[s.path], now) }
        }
        list = order(list, stats)
        if (!limitEnabled) return list
        val out = ArrayList<Song>()
        var total = 0.0
        val cap = when (limitUnit) {
            LimitUnit.ITEMS -> limitValue.toDouble()
            LimitUnit.MINUTES -> limitValue * 60_000.0
            LimitUnit.HOURS -> limitValue * 3_600_000.0
            LimitUnit.MB -> limitValue * 1_048_576.0
            LimitUnit.GB -> limitValue * 1_073_741_824.0
        }
        for (s in list) {
            val add = when (limitUnit) {
                LimitUnit.ITEMS -> 1.0
                LimitUnit.MINUTES, LimitUnit.HOURS -> s.duration.toDouble()
                LimitUnit.MB, LimitUnit.GB -> s.size.toDouble()
            }
            if (total + add > cap && out.isNotEmpty()) break
            total += add
            out += s
        }
        return out
    }

    private fun order(list: List<Song>, stats: Map<String, SongStats>): List<Song> {
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        fun st(s: Song) = stats[s.path] ?: SongStats()
        return when (selectedBy) {
            SelectedBy.RANDOM -> list.shuffled()
            SelectedBy.TITLE -> list.sortedWith(compareBy(collator) { it.title })
            SelectedBy.ALBUM -> list.sortedWith(compareBy<Song, String>(collator) { it.album }.thenBy { it.disc }.thenBy { it.track })
            SelectedBy.ARTIST -> list.sortedWith(compareBy(collator) { it.artist })
            SelectedBy.HIGHEST_RATING -> list.sortedByDescending { st(it).rating }
            SelectedBy.LOWEST_RATING -> list.sortedBy { st(it).rating }
            SelectedBy.RECENTLY_PLAYED -> list.sortedByDescending { st(it).lastPlayed }
            SelectedBy.LEAST_RECENTLY_PLAYED -> list.sortedBy { st(it).lastPlayed }
            SelectedBy.MOST_PLAYED -> list.sortedByDescending { st(it).playCount }
            SelectedBy.LEAST_PLAYED -> list.sortedBy { st(it).playCount }
            SelectedBy.RECENTLY_ADDED -> list.sortedByDescending { it.dateAdded }
            SelectedBy.LEAST_RECENTLY_ADDED -> list.sortedBy { it.dateAdded }
        }
    }

    private fun matches(r: Rule, s: Song, st: SongStats?, now: Long): Boolean {
        val stats = st ?: SongStats()
        return when (r.field.kind) {
            Kind.TEXT -> {
                val v = when (r.field) {
                    Field.TITLE -> s.title
                    Field.ALBUM -> s.album
                    Field.ARTIST -> s.artist
                    Field.ALBUM_ARTIST -> s.effectiveAlbumArtist
                    Field.COMPOSER -> s.composer
                    Field.GENRE -> s.genre
                    else -> s.path
                }
                val q = r.value
                when (r.op) {
                    Op.CONTAINS -> v.contains(q, true)
                    Op.NOT_CONTAINS -> !v.contains(q, true)
                    Op.IS -> v.equals(q, true)
                    Op.IS_NOT -> !v.equals(q, true)
                    Op.STARTS_WITH -> v.startsWith(q, true)
                    Op.ENDS_WITH -> v.endsWith(q, true)
                    else -> false
                }
            }
            Kind.NUMBER -> {
                val v: Double = when (r.field) {
                    Field.DURATION -> s.duration / 1000.0
                    Field.YEAR -> s.year.toDouble()
                    Field.RATING -> stats.rating / 2.0
                    Field.PLAY_COUNT -> stats.playCount.toDouble()
                    else -> stats.skipCount.toDouble()
                }
                val q = r.value.replace(',', '.').toDoubleOrNull() ?: return false
                val target = if (r.field == Field.DURATION) q * (r.unit.millis / 1000.0) else q
                when (r.op) {
                    Op.IS -> v == target
                    Op.IS_NOT -> v != target
                    Op.GREATER -> v > target
                    Op.LESS -> v < target
                    else -> false
                }
            }
            Kind.DATE -> {
                val v = when (r.field) {
                    Field.DATE_ADDED -> s.dateAdded * 1000
                    Field.DATE_MODIFIED -> s.dateModified * 1000
                    else -> stats.lastPlayed
                }
                when (r.op) {
                    Op.IN_LAST, Op.NOT_IN_LAST -> {
                        val n = r.value.toLongOrNull() ?: return false
                        val inside = v > 0 && now - v <= n * r.unit.millis
                        if (r.op == Op.IN_LAST) inside else !inside
                    }
                    Op.AFTER, Op.BEFORE -> {
                        val t = parseDate(r.value) ?: return false
                        if (r.op == Op.AFTER) v > t else v in 1 until t
                    }
                    else -> false
                }
            }
            Kind.BOOL -> if (r.op == Op.TRUE) s.isPodcast else !s.isPodcast
        }
    }

    companion object {
        const val RECENTLY_ADDED = "ra"
        const val TOP_RATED = "tr"
        const val MOST_PLAYED = "mp"
        const val RECENTLY_PLAYED = "rp"
        const val LEAST_PLAYED = "lp"
        const val PODCASTS = "pd"
        val BUILTIN_KEYS = listOf(RECENTLY_ADDED, TOP_RATED, MOST_PLAYED, RECENTLY_PLAYED, LEAST_PLAYED, PODCASTS)

        fun builtin(key: String, name: String): SmartPlaylist = when (key) {
            RECENTLY_ADDED -> SmartPlaylist(-1, name, rules = emptyList(), limitEnabled = true, limitValue = 100,
                selectedBy = SelectedBy.RECENTLY_ADDED, builtinKey = key)
            TOP_RATED -> SmartPlaylist(-2, name, rules = listOf(Rule(Field.RATING, Op.GREATER, "3.5")), limitEnabled = true,
                limitValue = 100, selectedBy = SelectedBy.HIGHEST_RATING, builtinKey = key)
            MOST_PLAYED -> SmartPlaylist(-3, name, rules = listOf(Rule(Field.PLAY_COUNT, Op.GREATER, "0")), limitEnabled = true,
                limitValue = 100, selectedBy = SelectedBy.MOST_PLAYED, builtinKey = key)
            RECENTLY_PLAYED -> SmartPlaylist(-4, name, rules = listOf(Rule(Field.PLAY_COUNT, Op.GREATER, "0")), limitEnabled = true,
                limitValue = 100, selectedBy = SelectedBy.RECENTLY_PLAYED, builtinKey = key)
            LEAST_PLAYED -> SmartPlaylist(-5, name, limitEnabled = true, limitValue = 100,
                selectedBy = SelectedBy.LEAST_PLAYED, builtinKey = key)
            else -> SmartPlaylist(-6, name, rules = listOf(Rule(Field.PODCAST, Op.TRUE, "")),
                selectedBy = SelectedBy.RECENTLY_ADDED, builtinKey = key)
        }

        fun fromJson(id: Long, name: String, json: String): SmartPlaylist? = try {
            val o = JSONObject(json)
            val arr = o.optJSONArray("rules") ?: JSONArray()
            val rules = (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                Rule(Field.from(r.getString("field")), Op.from(r.getString("op")), r.optString("value"), TimeUnit.from(r.optString("unit")))
            }
            SmartPlaylist(
                id, name, o.optString("match", "all") == "all", rules, o.optBoolean("limit"),
                o.optInt("limit_value", 25), LimitUnit.from(o.optString("limit_unit")), SelectedBy.from(o.optString("selected_by"))
            )
        } catch (_: Exception) {
            null
        }

        private fun parseDate(text: String): Long? = try {
            java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY).parse(text)?.time
        } catch (_: Exception) {
            null
        }
    }
}
