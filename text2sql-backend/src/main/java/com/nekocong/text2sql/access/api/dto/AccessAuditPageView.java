package com.nekocong.text2sql.access.api.dto;

import java.util.List;

/**
 * A page of the access-control audit trail: the entries for one page plus the total count matching
 * the filters.
 *
 * @param items the entries on this page, ordered newest first
 * @param total the total number of entries matching the filters (across all pages)
 * @param page the zero-based page number
 * @param size the effective page size (the requested size capped at the service maximum)
 */
public record AccessAuditPageView(List<AccessAuditView> items, long total, int page, int size) {

    /** Normalizes the item list so the page is structurally immutable. */
    public AccessAuditPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
