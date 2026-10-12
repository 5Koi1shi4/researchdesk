package com.researchdesk.report;

import com.researchdesk.identity.ApiFailure;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GeneratedReportLookup {
 private final JdbcTemplate db;private final ReportService reports;
 public GeneratedReportLookup(JdbcTemplate db,ReportService reports){this.db=db;this.reports=reports;}
 public ReportService.Report find(UUID run,UUID owner){
  var ids=db.queryForList("SELECT r.id FROM business.reports r JOIN business.generation_runs g ON g.id=r.source_run_id AND g.project_id=r.project_id JOIN business.projects p ON p.id=g.project_id WHERE g.id=? AND p.owner_id=? AND g.owner_id=p.owner_id AND g.kind='REPORT' AND g.state='COMPLETED'",UUID.class,run,owner);
  if(ids.isEmpty())throw new ApiFailure(404,"REPORT_NOT_FOUND","报告尚未生成或不存在。");
  return reports.require(ids.getFirst(),owner);
 }
}
