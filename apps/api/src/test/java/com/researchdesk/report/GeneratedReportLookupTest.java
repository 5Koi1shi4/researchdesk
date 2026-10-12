package com.researchdesk.report;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.researchdesk.identity.ApiFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GeneratedReportLookupTest {
 @Test void foreignOrUnknownRunCannotResolveReport() {
  var db=mock(JdbcTemplate.class);var reports=mock(ReportService.class);
  var run=UUID.randomUUID();var owner=UUID.randomUUID();
  when(db.queryForList(anyString(),eq(UUID.class),eq(run),eq(owner))).thenReturn(List.of());
  assertThatThrownBy(()->new GeneratedReportLookup(db,reports).find(run,owner)).isInstanceOf(ApiFailure.class);
  verifyNoInteractions(reports);
 }
 @Test void resolvesExactlyTheRunReportForTheOwner() {
  var db=mock(JdbcTemplate.class);var reports=mock(ReportService.class);
  var run=UUID.randomUUID();var owner=UUID.randomUUID();var report=UUID.randomUUID();
  when(db.queryForList(anyString(),eq(UUID.class),eq(run),eq(owner))).thenReturn(List.of(report));
  new GeneratedReportLookup(db,reports).find(run,owner);
  verify(reports).require(report,owner);
 }
}
