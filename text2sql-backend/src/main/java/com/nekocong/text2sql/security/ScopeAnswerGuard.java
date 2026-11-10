package com.nekocong.text2sql.security;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic backstop for the answer semantics of a scoped account: a restricted caller's answer
 * must never present a permission filter as "the data does not exist".
 *
 * <p>Given the caller's scope and the final answer text, it returns a standard clarification to be
 * appended when the answer asserts a negative (a "not found" / "only this exists" conclusion) and
 * does not already explain the data range. It never claims that out-of-range data exists — only that
 * the conclusion may be an artefact of the range — and it never fires for a caller that bypasses
 * filtering (an administrator).
 *
 * <p>Stateless; safe to share.
 */
public final class ScopeAnswerGuard {

    /** Phrases that turn an scoped result into an absolute claim about the whole database. */
    private static final List<String> NEGATIVE_ASSERTIONS =
            List.of("不存在", "没有数据", "数据库中只有", "未找到", "没有找到", "无数据");

    /** Phrases an answer already uses when it explains the range; no clarification is needed then. */
    private static final List<String> SCOPE_STATEMENTS = List.of(
            "数据范围",
            "权限范围",
            "仅本人",
            "所管班级",
            "不可见",
            "无权查看",
            "仅限",
            "可查看范围");

    private final DataScopeDescriber describer;

    /** Creates the guard with the shared range describer. */
    public ScopeAnswerGuard() {
        this(new DataScopeDescriber());
    }

    /**
     * Creates the guard.
     *
     * @param describer the range describer used for the clarification wording
     */
    public ScopeAnswerGuard(DataScopeDescriber describer) {
        this.describer = describer;
    }

    /**
     * Decides whether the final answer needs a range clarification appended.
     *
     * @param scope the caller's data scope
     * @param answer the answer text accumulated from the agent's deltas (may be {@code null})
     * @return the clarification to append, or {@code null} when none is needed
     */
    public String clarification(DataScope scope, String answer) {
        if (!describer.restricted(scope) || answer == null || answer.isBlank()) {
            return null;
        }
        String normalized = answer.toLowerCase(Locale.ROOT);
        if (!containsAny(normalized, NEGATIVE_ASSERTIONS)) {
            return null;
        }
        if (containsAny(normalized, SCOPE_STATEMENTS)) {
            return null;
        }
        return "（说明：当前账号的数据范围仅限"
                + describer.summary(scope)
                + "；未返回的数据可能在该范围之外，不能据此判断数据库中不存在。）";
    }

    private static boolean containsAny(String text, List<String> phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}
