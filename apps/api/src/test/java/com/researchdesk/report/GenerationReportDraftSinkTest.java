package com.researchdesk.report;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GenerationReportDraftSinkTest {
 @Test void mapsFrozenRunMetadataIntoNewDraft() {
  var db=mock(JdbcTemplate.class);var reports=mock(ReportService.class);
  var run=UUID.randomUUID();var project=UUID.randomUUID();var owner=UUID.randomUUID();var version=UUID.randomUUID();
  when(db.queryForMap(anyString(),eq(run),eq(project))).thenReturn(Map.of("owner_id",owner,"prompt","研究主题","document_version_ids","[\""+version+"\"]","mode","DEMO"));
  new GenerationReportDraftSink(db,reports,new ObjectMapper()).saveDraft(run.toString(),project.toString(),"报告",List.of());
  verify(reports).completeDraft(project,owner,run,"研究主题","报告",List.of(),List.of(version),true);
 }
}
