package com.nekocong.text2sql.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.conversation.Conversation;
import com.nekocong.text2sql.repository.UserRepository;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.service.ConversationService;
import com.nekocong.text2sql.user.AppUser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Loads the {@code db/app-data.sql} seed script into the test database and asserts the dataset it
 * produces, including the roles and data-scope mappings the permission layer depends on.
 *
 * <p>This keeps the seed script honest: a syntax error, a wrong role, a missing scope row or a
 * missing conversation is caught here instead of on a fresh server.
 */
@SpringBootTest
class SeedDataScriptTest {

    @Autowired
    private DatabaseClient client;

    @Autowired
    private UserRepository users;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void seedScriptCreatesCampusAccountsScopesAndOwnedConversations() {
        runScript("db/app-data.sql");

        AppUser admin = users.findByUsername("admin").block();
        assertThat(admin).isNotNull();
        assertThat(admin.role()).isEqualTo(Roles.ADMIN);
        assertThat(admin.isAdmin()).isTrue();

        AppUser student01 = users.findByUsername("student01").block();
        AppUser student02 = users.findByUsername("student02").block();
        AppUser student03 = users.findByUsername("student03").block();
        AppUser counselor01 = users.findByUsername("counselor01").block();

        assertThat(student01.role()).isEqualTo(Roles.STUDENT);
        assertThat(student02.role()).isEqualTo(Roles.STUDENT);
        assertThat(student03.role()).isEqualTo(Roles.STUDENT);
        assertThat(counselor01.role()).isEqualTo(Roles.COUNSELOR);
        assertThat(student01.isAdmin()).isFalse();

        // The legacy e-commerce accounts are gone with the dataset they belonged to.
        assertThat(users.findByUsername("alice").block()).isNull();
        assertThat(users.findByUsername("bob").block()).isNull();

        // The bcrypt hashes must match the passwords documented in app-data.sql, docs/deploy.md and
        // deploy/bootstrap.sh. A wrong hash would only surface on a freshly deployed server.
        assertThat(passwordEncoder.matches("admin123", admin.passwordHash())).isTrue();
        assertThat(passwordEncoder.matches("student123", student01.passwordHash())).isTrue();
        assertThat(passwordEncoder.matches("counselor123", counselor01.passwordHash())).isTrue();

        // Students are scoped to themselves; the counselor is scoped to the two classes it manages.
        assertThat(scopeValues(student01.id(), "class_id")).containsExactly("1");
        assertThat(scopeValues(student01.id(), "student_id")).containsExactly("1");
        assertThat(scopeValues(student01.id(), "student_no")).containsExactly("2023001");
        assertThat(scopeValues(student03.id(), "class_id")).containsExactly("2");
        assertThat(scopeValues(counselor01.id(), "class_id")).containsExactlyInAnyOrder("1", "2");

        // The datasource whitelist is seeded for the scoped roles only; admins need no row.
        assertThat(datasourceAccess("student")).containsExactly("campus");
        assertThat(datasourceAccess("counselor")).containsExactly("campus");
        assertThat(datasourceAccess("admin")).isEmpty();

        // A student only sees the conversations it owns.
        assertThat(ids(conversations.list(student01.id(), false).collectList().block()))
                .containsExactlyInAnyOrder("seed-conv-student01-1", "seed-conv-student01-2");
        assertThat(ids(conversations.list(student02.id(), false).collectList().block()))
                .containsExactly("seed-conv-student02-1");

        // An admin sees every seeded conversation, whoever owns it.
        assertThat(ids(conversations.list(admin.id(), true).collectList().block()))
                .contains(
                        "seed-conv-student01-1",
                        "seed-conv-student01-2",
                        "seed-conv-student02-1",
                        "seed-conv-counselor01-1",
                        "seed-conv-admin-1");

        // The seeded exchange is readable by its owner and references the campus dataset.
        assertThat(
                        conversations
                                .messages("seed-conv-student01-1", student01.id(), false)
                                .collectList()
                                .block())
                .hasSize(2);

        // Re-running the script must be a no-op: fixed ids plus INSERT IGNORE make the seed
        // re-executable, which matters because the same file runs on every startup when seeding.
        runScript("db/app-data.sql");
        assertThat(users.findByUsername("student01").block().id()).isEqualTo(student01.id());
        assertThat(scopeValues(student01.id(), "class_id")).containsExactly("1");
        assertThat(countRows("SELECT COUNT(*) AS n FROM app_user WHERE id LIKE 'seed-user-%'"))
                .isEqualTo(5);
        assertThat(countRows("SELECT COUNT(*) AS n FROM user_scope WHERE user_id LIKE 'seed-user-%'"))
                .isEqualTo(11);
        assertThat(
                        countRows(
                                "SELECT COUNT(*) AS n FROM conversation WHERE id LIKE 'seed-conv-%'"))
                .isEqualTo(5);
    }

    private Long countRows(String sql) {
        return client.sql(sql).map((row, meta) -> row.get("n", Long.class)).one().block();
    }

    private List<String> scopeValues(String userId, String scopeType) {
        return client.sql(
                        "SELECT scope_value FROM user_scope WHERE user_id = :userId AND scope_type = :scopeType")
                .bind("userId", userId)
                .bind("scopeType", scopeType)
                .map((row, meta) -> row.get("scope_value", String.class))
                .all()
                .collectList()
                .block();
    }

    private List<String> datasourceAccess(String role) {
        return client.sql("SELECT datasource_id FROM datasource_access WHERE role = :role")
                .bind("role", role)
                .map((row, meta) -> row.get("datasource_id", String.class))
                .all()
                .collectList()
                .block();
    }

    private static List<String> ids(List<Conversation> rows) {
        return rows.stream().map(Conversation::id).toList();
    }

    private void runScript(String location) {
        String sql;
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + location, ex);
        }
        String body =
                sql.lines()
                        .filter(line -> !line.stripLeading().startsWith("--"))
                        .collect(Collectors.joining("\n"));
        for (String statement : body.split(";")) {
            String trimmed = statement.strip();
            if (!trimmed.isEmpty()) {
                client.sql(trimmed).then().block();
            }
        }
    }
}
