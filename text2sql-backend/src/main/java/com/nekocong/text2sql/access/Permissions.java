package com.nekocong.text2sql.access;

/** The seeded permission keys referenced by enforcement code. */
public final class Permissions {

    /** Use the chat / NL2SQL surfaces. */
    public static final String CHAT_USE = "chat.use";

    /** Read datasource metadata. */
    public static final String DATASOURCE_READ = "datasource.read";

    /** Create/replace/delete datasources. */
    public static final String DATASOURCE_WRITE = "datasource.write";

    /** Manage model configurations. */
    public static final String MODEL_MANAGE = "model.manage";

    /** Manage roles, permissions and data scopes. */
    public static final String ACCESS_MANAGE = "access.manage";

    /** Read the user list. */
    public static final String USER_READ = "user.read";

    /** Create/disable/enable/reset users and assign roles. */
    public static final String USER_MANAGE = "user.manage";

    /** Read the access-control audit log. */
    public static final String AUDIT_READ = "audit.read";

    /** Bypass data-scope filtering (granted to the built-in administrator role by seed data). */
    public static final String BYPASS_DATA_SCOPE = "access.bypass_data_scope";

    private Permissions() {}
}
