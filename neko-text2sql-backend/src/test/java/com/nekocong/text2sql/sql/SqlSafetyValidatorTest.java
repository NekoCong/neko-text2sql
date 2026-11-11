package com.nekocong.text2sql.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit tests for the read-only SQL validator. Pure, no Spring context. */
class SqlSafetyValidatorTest {

    private final SqlSafetyValidator validator = new SqlSafetyValidator();

    @Test
    void acceptsPlainSelect() {
        assertThat(validator.validateAndNormalize("SELECT 1")).isEqualTo("SELECT 1");
    }

    @Test
    void stripsTrailingSemicolon() {
        assertThat(validator.validateAndNormalize("SELECT id FROM orders;")).isEqualTo("SELECT id FROM orders");
    }

    @Test
    void stripsLeadingLineComment() {
        assertThat(validator.validateAndNormalize("-- generated\nSELECT 1")).isEqualTo("SELECT 1");
    }

    @Test
    void stripsLeadingBlockComment() {
        assertThat(validator.validateAndNormalize("/* why */ SELECT 1")).isEqualTo("SELECT 1");
    }

    @Test
    void acceptsWithCte() {
        assertThat(validator.validateAndNormalize("WITH t AS (SELECT 1) SELECT * FROM t"))
                .startsWith("WITH");
    }

    @Test
    void allowsSemicolonInsideStringLiteral() {
        assertThat(validator.validateAndNormalize("SELECT ';' AS s")).isEqualTo("SELECT ';' AS s");
    }

    @Test
    void allowsCreatedAtColumn() {
        // 'created_at' must not be mistaken for the forbidden CREATE keyword.
        assertThat(validator.validateAndNormalize("SELECT created_at FROM orders"))
                .isEqualTo("SELECT created_at FROM orders");
    }

    @Test
    void rejectsMultipleStatements() {
        assertThatThrownBy(() -> validator.validateAndNormalize("SELECT 1; DROP TABLE orders"))
                .isInstanceOf(SqlSafetyException.class);
    }

    @Test
    void rejectsDelete() {
        assertThatThrownBy(() -> validator.validateAndNormalize("DELETE FROM orders"))
                .isInstanceOf(SqlSafetyException.class);
    }

    @Test
    void rejectsUpdate() {
        assertThatThrownBy(() -> validator.validateAndNormalize("UPDATE orders SET status='PAID'"))
                .isInstanceOf(SqlSafetyException.class);
    }

    @Test
    void rejectsDrop() {
        assertThatThrownBy(() -> validator.validateAndNormalize("DROP TABLE orders"))
                .isInstanceOf(SqlSafetyException.class);
    }

    @Test
    void rejectsNonSelectStatement() {
        assertThatThrownBy(() -> validator.validateAndNormalize("SHOW TABLES"))
                .isInstanceOf(SqlSafetyException.class);
    }

    @Test
    void rejectsEmpty() {
        assertThatThrownBy(() -> validator.validateAndNormalize("   "))
                .isInstanceOf(SqlSafetyException.class);
    }
}
