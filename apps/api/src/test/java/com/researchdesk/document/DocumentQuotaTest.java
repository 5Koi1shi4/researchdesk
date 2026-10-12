package com.researchdesk.document;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchdesk.identity.ApiFailure;
import com.researchdesk.project.ProjectService;
import java.util.*;
import java.sql.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class DocumentQuotaTest {
    @Test void rejectionReportsTheConfiguredQuota() throws Exception {
        var db=mock(JdbcTemplate.class); var source=mock(DataSource.class);
        var connection=mock(Connection.class); var statement=mock(PreparedStatement.class);
        when(db.getDataSource()).thenReturn(source);when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        UUID project=UUID.randomUUID(),owner=UUID.randomUUID();
        when(db.queryForList(anyString(),eq(project),eq(owner))).thenReturn(List.of(Map.of("id",project)));
        when(db.queryForObject(startsWith("SELECT count(*) FROM business.documents"),eq(Integer.class),eq(project))).thenReturn(2);
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var service=new DocumentService(db,manager,mock(ProjectService.class),mock(ObjectStorage.class),
            new UploadValidator(1024,2),new ObjectMapper(),2);
        assertThatThrownBy(()->service.upload(project,owner,new MockMultipartFile("file","three.md","text/markdown","# Third".getBytes())))
            .isInstanceOf(ApiFailure.class).hasMessageContaining("2").hasMessageNotContaining("50");
    }
}
