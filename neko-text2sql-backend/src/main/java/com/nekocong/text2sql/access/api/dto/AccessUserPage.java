package com.nekocong.text2sql.access.api.dto;

import com.nekocong.text2sql.access.AccessUser;
import java.util.List;

/**
 * A page of users: the items for one page plus the total count matching the filters.
 *
 * @param items the users on this page, ordered by username
 * @param total the total number of users matching the filters (across all pages)
 * @param page the zero-based page number
 * @param size the effective page size (the requested size capped at the service maximum)
 */
public record AccessUserPage(List<AccessUserView> items, long total, int page, int size) {

    /** Normalizes the item list so the page is structurally immutable. */
    public AccessUserPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * Maps a page of domain users to its view.
     *
     * @param users the users on this page
     * @param total the total matching count
     * @param page the zero-based page number
     * @param size the effective page size
     * @return the page
     */
    public static AccessUserPage of(List<AccessUser> users, long total, int page, int size) {
        return new AccessUserPage(
                users.stream().map(AccessUserView::of).toList(), total, page, size);
    }
}
