package com.jonoshields.gossip.core.data

/**
 * The largest number of values to bind into one `IN (:ids)` clause.
 *
 * Room expands `IN (:ids)` into one **bound variable per element**, and SQLite caps the
 * variables in a single statement: 999 on older Android builds, 32766 on newer ones. Neither
 * limit is a suggestion — going over throws rather than truncating.
 *
 * 900 is chosen under the smaller of the two, because the code cannot tell which SQLite it is
 * running against and picking the larger would work in testing and fail on exactly the older
 * phones this project targets (`minSdk 33` still reaches devices on the old limit).
 *
 * This matters here more than in most apps: a prune evicting thousands of ids at once is the
 * *normal* case for a store that fills to a cap by design, not an edge case.
 */
internal const val SQL_VARIABLE_LIMIT: Int = 900

/**
 * Runs [query] over [values] in batches small enough for SQLite, concatenating the results.
 *
 * Only sound for queries whose result is the **union** over the batches — `IN`, and any
 * `DELETE ... WHERE x IN`. A `NOT IN` is the intersection instead and would be silently wrong
 * chunked this way, which is why nothing here does it.
 */
internal suspend fun <T, R> chunked(
    values: Collection<T>,
    query: suspend (List<T>) -> List<R>,
): List<R> = when {
    values.isEmpty() -> emptyList()
    values.size <= SQL_VARIABLE_LIMIT -> query(values.toList())
    else -> values.chunked(SQL_VARIABLE_LIMIT).flatMap { query(it) }
}

/** As [chunked], for statements that return nothing. */
internal suspend fun <T> chunkedAction(
    values: Collection<T>,
    statement: suspend (List<T>) -> Unit,
) {
    if (values.isEmpty()) return
    values.chunked(SQL_VARIABLE_LIMIT).forEach { statement(it) }
}
