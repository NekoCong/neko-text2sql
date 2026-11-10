package com.nekocong.text2sql.security;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Describes a caller's {@link DataScope} in the two registers the product needs: a short summary
 * for the deterministic answer guard, and the full block injected into the generation / agent
 * prompts.
 *
 * <p>Both forms come from one place so the deterministic pipeline and the chat agent never drift.
 * They only ever mention the caller's <em>own</em> identifiers (the caller's student number and the
 * ids of the classes it may see): names of tables or classes outside the scope and other students'
 * numbers are never rendered, and table/column visibility stays with {@link TablePolicy}.
 *
 * <p>Stateless; safe to share.
 */
public final class DataScopeDescriber {

    /** The wording used for a caller that bypasses row filtering. */
    static final String UNRESTRICTED_SUMMARY = "全量数据，不受范围限制";

    /** Creates the describer; it holds no state, so one instance can serve every request. */
    public DataScopeDescriber() {}

    /**
     * Reports whether the scope is subject to row-level filtering.
     *
     * <p>Fail closed: a scope without resolved access is reported as restricted, because it sees
     * nothing.
     *
     * @param scope the caller's data scope (may be {@code null})
     * @return {@code true} when the caller does not bypass the data scope
     */
    public boolean restricted(DataScope scope) {
        return scope == null || !scope.bypassesDataScope();
    }

    /**
     * The short, one-line range description used by the answer guard, e.g. {@code 学生（仅本人：学号
     * 2023002，班级 1）} for a student, {@code 辅导员（所管班级：1、2）} for a counselor and {@link
     * #UNRESTRICTED_SUMMARY} for an administrator.
     *
     * @param scope the caller's data scope (may be {@code null})
     * @return the short range description; never {@code null}
     */
    public String summary(DataScope scope) {
        if (scope == null || scope.access() == null) {
            return "无可访问的数据范围";
        }
        if (scope.bypassesDataScope()) {
            return UNRESTRICTED_SUMMARY;
        }
        String kind = kind(scope);
        if (scope.studentNo() != null) {
            return kind
                    + "（仅本人：学号 "
                    + scope.studentNo()
                    + classSuffix(scope)
                    + "）";
        }
        if (!scope.classIds().isEmpty()) {
            return kind + "（仅限所管班级：" + classList(scope) + "）";
        }
        return kind + "（范围由当前账号的访问策略决定）";
    }

    /**
     * The full caller block injected into the generation prompt (pipeline) and the agent system
     * prompt, so first-person questions resolve to the caller's own identifiers instead of being
     * guessed from the question text.
     *
     * <p>The wording is derived from the resolved access: a bypass (administrator) is unrestricted;
     * otherwise the effective role display names are used with the caller's principal identifiers.
     *
     * @param scope the caller's data scope (may be {@code null})
     * @return the caller context block; never {@code null}
     */
    public String promptBlock(DataScope scope) {
        if (scope == null || scope.access() == null) {
            return "当前账号未解析出数据范围，只能看到空结果。";
        }
        if (scope.bypassesDataScope()) {
            return "管理员：可见全部数据，不需要按调用者过滤。";
        }
        List<String> roleNames = scope.access().roles().stream().map(role -> role.name()).toList();
        String description =
                roleNames.isEmpty()
                        ? scope.access().roleCodes().toString()
                        : String.join("、", roleNames);
        StringBuilder context = new StringBuilder(description).append("：");
        if (scope.studentNo() != null) {
            context.append("本人学号 ")
                    .append(scope.studentNo())
                    .append("，所属班级 id ")
                    .append(scope.classIds().stream().sorted().toList())
                    .append("。问题中的“我/我的”指该学生本人，用 student_no = '")
                    .append(scope.studentNo())
                    .append("' 过滤。");
        } else if (!scope.classIds().isEmpty()) {
            context.append("管理班级 id ")
                    .append(scope.classIds().stream().sorted().toList())
                    .append("。问题中的“我管理的班/本班”指这些班级，用 class_id IN (...) 过滤。");
        } else {
            context.append("数据范围由当前账号的访问策略决定，只能查询策略允许的数据。");
        }
        return context.toString();
    }

    /** The readable role name for the summary; the enforcement layer never branches on it. */
    private static String kind(DataScope scope) {
        if (scope.isStudent()) {
            return "学生";
        }
        if (scope.isCounselor()) {
            return "辅导员";
        }
        List<String> roleNames = scope.access().roles().stream().map(role -> role.name()).toList();
        if (!roleNames.isEmpty()) {
            return String.join("、", roleNames);
        }
        return "受限账号";
    }

    /** The class the caller belongs to, when it is exactly one; otherwise nothing. */
    private static String classSuffix(DataScope scope) {
        return scope.classIds().size() == 1 ? "，班级 " + classList(scope) : "";
    }

    private static String classList(DataScope scope) {
        return scope.classIds().stream().sorted().map(String::valueOf).collect(Collectors.joining("、"));
    }
}
