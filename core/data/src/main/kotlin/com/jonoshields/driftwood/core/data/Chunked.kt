package com.jonoshields.driftwood.core.data

/** The largest number of values to bind into one `IN (:ids)` clause — under SQLite's lowest variable cap (999), since we can't tell which limit applies. */
internal const val SQL_VARIABLE_LIMIT: Int = 900

/** Runs [query] over [values] in batches small enough for SQLite; only sound for union-shaped results (`IN`, not `NOT IN`). */
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
