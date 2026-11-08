package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.controller.Text2SqlController;
import com.nekocong.text2sql.conversation.Conversation;
import com.nekocong.text2sql.conversation.ConversationMessage;
import com.nekocong.text2sql.gbi.ChartSpec;
import com.nekocong.text2sql.gbi.ChartType;
import com.nekocong.text2sql.nl2sql.ChartEvent;
import com.nekocong.text2sql.nl2sql.RowsEvent;
import com.nekocong.text2sql.nl2sql.SqlEvent;
import com.nekocong.text2sql.nl2sql.StepEvent;
import com.nekocong.text2sql.nl2sql.Text2SqlEvent;
import com.nekocong.text2sql.service.ConversationService;
import com.nekocong.text2sql.service.Text2SqlAgentService;
import com.nekocong.text2sql.service.Text2SqlPipelineService;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.DataScopeResolver;
import com.nekocong.text2sql.sql.model.ColumnMeta;
import com.nekocong.text2sql.sql.model.QueryResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;

/**
 * Regression test: the chat stream must persist the assistant message with the generated SQL and
 * result rows (so reloading / switching conversation renders the answer again). Guards against the
 * earlier bug where the persistence {@code Mono} was built eagerly and captured empty values.
 */
@SpringBootTest
class Text2SqlControllerPersistenceTest {

    @Autowired
    private ConversationService conversations;

    @Autowired
    private Text2SqlAgentService agentService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataScopeResolver scopes;

    @Autowired
    private DatabaseClient client;

    @Test
    void persistsAssistantAnswerWithSqlRowsAndChart() {
        String userId = "u-" + UUID.randomUUID();
        scope(userId);
        Conversation conversation = conversations.create(userId, "persist", "campus", null).block();
        assertThat(conversation).isNotNull();

        Text2SqlPipelineService stubPipeline =
                new Text2SqlPipelineService(null, null, null, null, null, null, null, null, null) {
                    @Override
                    public Flux<Text2SqlEvent> stream(
                            DataScope scope, String datasourceId, String question) {
                        QueryResult rows =
                                new QueryResult(
                                        List.of(new ColumnMeta("c", "BIGINT")),
                                        List.of(Map.<String, Object>of("c", 1)),
                                        1,
                                        false,
                                        1L);
                        return Flux.just(
                                new StepEvent("introspect", "读取结构"),
                                new SqlEvent("campus", "H2", "SELECT 1 AS c", "统计计数"),
                                new RowsEvent(rows),
                                new ChartEvent(new ChartSpec(ChartType.NONE, null, null, null)));
                    }
                };

        Text2SqlController controller =
                new Text2SqlController(
                        stubPipeline,
                        agentService,
                        conversations,
                        scopes,
                        new com.nekocong.text2sql.controller.AgentEventPresenter(objectMapper),
                        objectMapper);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(userId).claim("role", "student").claim("ver", 0).build();

        controller.stream(jwt, "统计计数", "campus", conversation.id()).collectList().block();

        List<ConversationMessage> messages =
                conversations.messages(conversation.id(), userId, false).collectList().block();
        assertThat(messages).isNotNull();
        ConversationMessage assistant =
                messages.stream().filter(m -> "assistant".equals(m.role())).findFirst().orElseThrow();
        assertThat(assistant.sql()).isEqualTo("SELECT 1 AS c");
        assertThat(assistant.content()).isEqualTo("统计计数");
        assertThat(assistant.rowsJson()).contains("\"c\"");
    }

    private void scope(String userId) {
        insertScope(userId, "class_id", "1");
        insertScope(userId, "student_id", "1");
        insertScope(userId, "student_no", "2023001");
    }

    private void insertScope(String userId, String type, String value) {
        client.sql(
                        "INSERT INTO user_scope (user_id, scope_type, scope_value) "
                                + "VALUES (:userId, :type, :value)")
                .bind("userId", userId)
                .bind("type", type)
                .bind("value", value)
                .then()
                .block();
    }
}
