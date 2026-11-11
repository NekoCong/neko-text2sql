package com.nekocong.text2sql.knowledge.api.dto;

import java.util.List;

/**
 * The result of syncing one datasource's live schema into the metadata collection.
 *
 * @param datasourceId the datasource
 * @param tableCount the number of tables synced
 * @param itemIds the upserted item ids
 */
public record MetadataSyncView(String datasourceId, int tableCount, List<String> itemIds) {
}
