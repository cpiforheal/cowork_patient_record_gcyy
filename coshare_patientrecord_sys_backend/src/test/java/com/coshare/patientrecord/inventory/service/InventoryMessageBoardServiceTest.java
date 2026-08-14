package com.coshare.patientrecord.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class InventoryMessageBoardServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InventoryMessageBoardService service;
    private SessionUser author;

    @BeforeEach
    void setUp() {
        service = new InventoryMessageBoardService(jdbcTemplate, objectMapper);
        author = user("account-1", "理疗室");
    }

    @Test
    void publicPostListAlwaysExcludesHiddenPosts() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));

        service.posts(author, false, "", "", "", "", false, 1, 20);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(Long.class), any(Object[].class));
        assertThat(sql.getValue()).contains("p.hidden = FALSE");
    }

    @Test
    void createPostRejectsInvalidCategoryBeforeWriting() {
        ObjectNode payload = objectMapper.createObjectNode()
            .put("title", "新增耗材")
            .put("content", "请补充一项耗材")
            .put("category", "INVALID");

        assertThatThrownBy(() -> service.createPost(payload, author, false))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createPostUsesPendingStatusLiteralAndWritesAuditLog() {
        ObjectNode payload = objectMapper.createObjectNode()
            .put("title", "新增耗材")
            .put("content", "请补充一项耗材")
            .put("category", "NEW_ITEM");
        doReturn(Map.of("id", "post-1"))
            .when(jdbcTemplate).query(contains("SELECT p.*"), any(ResultSetExtractor.class), anyString());

        service.createPost(payload, author, false);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).anySatisfy(value -> assertThat(value).contains("'PENDING'"));
        assertThat(sql.getAllValues()).anySatisfy(value -> assertThat(value).contains("inventory_message_board_audit_logs"));
    }

    @Test
    void anotherDepartmentCannotEditPost() throws Exception {
        stubPostState("post-1", "account-1", false, false);
        SessionUser anotherUser = user("account-2", "中医科");
        ObjectNode payload = objectMapper.createObjectNode()
            .put("title", "修改主题")
            .put("content", "修改内容")
            .put("category", "SUGGESTION");

        assertThatThrownBy(() -> service.updatePost("post-1", payload, anotherUser, false))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void hiddenPostCannotBeEditedByItsAuthor() throws Exception {
        stubPostState("post-1", "account-1", true, false);
        ObjectNode payload = objectMapper.createObjectNode()
            .put("title", "修改主题")
            .put("content", "修改内容")
            .put("category", "SUGGESTION");

        assertThatThrownBy(() -> service.updatePost("post-1", payload, author, false))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void authorWithdrawalUsesSoftDeleteAndWritesAuditLog() throws Exception {
        stubPostState("post-1", "account-1", false, false);

        service.withdrawPost("post-1", author);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).anySatisfy(value -> assertThat(value).contains("withdrawn = TRUE"));
        assertThat(sql.getAllValues()).anySatisfy(value -> assertThat(value).contains("inventory_message_board_audit_logs"));
    }

    @Test
    void administratorStatusChangeWritesImmutableAuditEntry() throws Exception {
        stubPostState("post-1", "account-1", false, false);
        doReturn(Map.of("id", "post-1"))
            .when(jdbcTemplate).query(contains("SELECT p.*"), any(ResultSetExtractor.class), eq("post-1"));
        ObjectNode payload = objectMapper.createObjectNode()
            .put("status", "COMPLETED")
            .put("handlingNote", "已完成核对");

        service.updateStatus("post-1", payload, user("inventory-admin", "管理端"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).anySatisfy(value -> assertThat(value).contains("process_status = ?"));
        assertThat(sql.getAllValues()).anySatisfy(value -> assertThat(value).contains("inventory_message_board_audit_logs"));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void stubPostState(String postId, String authorId, boolean hidden, boolean withdrawn) throws Exception {
        doAnswer(invocation -> {
            ResultSetExtractor extractor = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getString("id")).thenReturn(postId);
            when(resultSet.getString("author_id")).thenReturn(authorId);
            when(resultSet.getBoolean("hidden")).thenReturn(hidden);
            when(resultSet.getBoolean("withdrawn")).thenReturn(withdrawn);
            return extractor.extractData(resultSet);
        }).when(jdbcTemplate).query(contains("SELECT id, author_id"), any(ResultSetExtractor.class), eq(postId));
    }

    private SessionUser user(String id, String department) {
        return new SessionUser(
            id,
            id,
            department + "填报员",
            "inventory_clerk",
            "科室填报员",
            department,
            department,
            false,
            Instant.now().plusSeconds(3600)
        );
    }
}
