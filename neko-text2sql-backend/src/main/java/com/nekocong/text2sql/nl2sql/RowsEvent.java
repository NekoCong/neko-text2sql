package com.nekocong.text2sql.nl2sql;

import com.nekocong.text2sql.sql.model.QueryResult;

/**
 * The result rows produced by executing a generated query.
 *
 * @param result the query result
 */
public record RowsEvent(QueryResult result) implements Text2SqlEvent {}
