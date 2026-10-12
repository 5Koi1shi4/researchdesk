package com.researchdesk.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchdesk.Contracts.Citation;
import com.researchdesk.research.ReportDraftSink;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bridges the fenced generation transaction to the versioned report module. */
@Component
public class GenerationReportDraftSink implements ReportDraftSink {
 private final JdbcTemplate db;
 private final ReportService reports;
 private final ObjectMapper json;
 public GenerationReportDraftSink(JdbcTemplate db, ReportService reports, ObjectMapper json) {
  this.db=db;this.reports=reports;this.json=json;
 }
 @Override
 @Transactional(propagation=Propagation.MANDATORY)
 public void saveDraft(String runId,String projectId,String markdown,List<Citation> citations) {
  UUID run=UUID.fromString(runId),project=UUID.fromString(projectId);
  var row=db.queryForMap("SELECT owner_id,prompt,document_version_ids::text,mode FROM business.generation_runs WHERE id=? AND project_id=? AND kind='REPORT' AND state='RUNNING'",run,project);
  List<UUID> allowed;
  try {allowed=json.readValue(row.get("document_version_ids").toString(),new TypeReference<List<UUID>>() {});}
  catch(Exception e){throw new IllegalStateException("Invalid frozen report scope",e);}
  String prompt=row.get("prompt").toString();
  // The proxied service joins this transaction and deduplicates source_run_id, preserving edits.
  reports.completeDraft(project,(UUID)row.get("owner_id"),run,prompt.substring(0,Math.min(200,prompt.length())),markdown,citations,allowed,"DEMO".equals(row.get("mode")));
 }
}
