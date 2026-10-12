package com.researchdesk.research;
import com.researchdesk.Contracts.Citation; import java.util.List;
/** Optional report module bean. Invoked within final-completion transaction; must not do network I/O.
 * Implementations must be idempotent by runId and create a NEW draft rather than overwrite edits. */
public interface ReportDraftSink { void saveDraft(String runId,String projectId,String markdown,List<Citation> citations); }
