package com.nekocong.text2sql.access;

/**
 * One entry of the seeded permission catalog.
 *
 * <p>The catalog is the authoritative list of grantable permission keys: a role's permission set may
 * only reference keys that appear here, so an unknown key is rejected instead of being silently
 * stored and never enforced. Keys are dotted {@code module.action} strings such as {@code chat.use};
 * enforcement code refers to them through {@link Permissions}.
 *
 * @param id the permission id
 * @param key the permission key ({@code access_permission.permission_key})
 * @param module the module the key belongs to ({@code chat}, {@code datasource}, ...)
 * @param action the action within the module ({@code use}, {@code read}, ...)
 * @param description the human-readable description, or {@code null}
 * @param builtIn whether the catalog row is seeded
 */
public record AccessPermission(
        String id,
        String key,
        String module,
        String action,
        String description,
        boolean builtIn) {
}
