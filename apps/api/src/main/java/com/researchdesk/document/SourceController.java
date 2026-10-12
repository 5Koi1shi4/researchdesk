package com.researchdesk.document;

import com.researchdesk.Contracts;
import com.researchdesk.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class SourceController {
 public record SourceInfo(String documentId,String documentVersionId,String title,String mediaType,Integer pageCount,Contracts.Availability availability) {}
 public record AvailabilityRequest(List<Contracts.Citation> citations) {}
 private final JdbcTemplate db;private final ObjectStorage storage;private final SessionService sessions;private final DeletionService deletion;private final PdfPreview preview;
 public SourceController(JdbcTemplate db,ObjectStorage storage,SessionService sessions,DeletionService deletion,PdfPreview preview){this.preview=preview;this.db=db;this.storage=storage;this.sessions=sessions;this.deletion=deletion;}
 private Map<String,Object> source(UUID id,UUID owner,boolean version){
  var rows=db.queryForList("SELECT d.id document_id,v.id version_id,d.title,d.state,v.media_type,v.page_count,v.object_key FROM business.documents d JOIN business.document_versions v ON v.document_id=d.id JOIN business.projects p ON p.id=d.project_id WHERE "+(version?"v.id":"d.id")+"=? AND p.owner_id=?",id,owner);
  if(rows.isEmpty())throw new ApiFailure(404,"SOURCE_NOT_FOUND","资料不存在。");return rows.getFirst();
 }
 private boolean deleted(Map<String,Object> r){return Set.of("DELETING","DELETED").contains(r.get("state"));}
 @GetMapping("/api/document-versions/{id}/source-info") public ResponseEntity<SourceInfo> info(@PathVariable UUID id,HttpServletRequest request){
  var r=source(id,sessions.require(request).user().id(),true);
  return ResponseEntity.ok().header("Cache-Control","no-store").body(new SourceInfo(r.get("document_id").toString(),id.toString(),r.get("title").toString(),r.get("media_type").toString(),(Integer)r.get("page_count"),deleted(r)?Contracts.Availability.DELETED:Contracts.Availability.AVAILABLE));
 }
 @GetMapping("/api/documents/{id}/source") public ResponseEntity<byte[]> content(@PathVariable UUID id,HttpServletRequest request){
  UUID owner=sessions.require(request).user().id();var r=source(id,owner,false);requireReadable(r);
  byte[] bytes=storage.read(r.get("object_key").toString());
  // Do not return a fetch that raced a committed revocation.
  requireReadable(source(id,owner,false));
  String type=r.get("media_type").equals("application/pdf")?"application/pdf":"text/plain; charset=UTF-8";
  return ResponseEntity.ok().header("Content-Type",type).header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox; default-src 'none'").header("Content-Disposition","inline").body(bytes);
 }
 private void requireReadable(Map<String,Object> r){if(deleted(r))throw new ApiFailure(410,"SOURCE_DELETED","原资料已删除；历史回答正文仍保留。");if(!r.get("state").equals("READY"))throw new ApiFailure(409,"SOURCE_NOT_READY","资料尚未处理完成。");}
 @PostMapping("/api/sources/availability") public ResponseEntity<List<Contracts.Citation>> availability(@RequestBody AvailabilityRequest body,HttpServletRequest request){
  UUID owner=sessions.require(request).user().id();
  if(body.citations()==null||body.citations().size()>200)throw new ApiFailure(400,"INVALID_CITATIONS","引用列表无效。");
  var result=new ArrayList<Contracts.Citation>();
  for(var c:body.citations()){
   UUID version;try{version=UUID.fromString(c.documentVersionId());}catch(RuntimeException e){throw new ApiFailure(400,"INVALID_CITATIONS","引用版本无效。");}
   var r=source(version,owner,true);boolean removed=deleted(r);
   result.add(new Contracts.Citation(c.chunkId(),c.documentVersionId(),c.title(),c.page(),c.paragraph(),removed?null:c.excerpt(),removed?Contracts.Availability.DELETED:Contracts.Availability.AVAILABLE));
  }
  return ResponseEntity.ok().header("Cache-Control","no-store").body(result);
 }
 @GetMapping("/api/documents/{id}/source/pages/{page}") public ResponseEntity<byte[]> page(@PathVariable UUID id,@PathVariable int page,HttpServletRequest request){
  UUID owner=sessions.require(request).user().id();var r=source(id,owner,false);requireReadable(r);
  if(!r.get("media_type").equals("application/pdf"))throw new ApiFailure(400,"INVALID_SOURCE_TYPE","仅 PDF 支持分页预览。");
  if(page<1||page>(Integer)r.get("page_count"))throw new ApiFailure(400,"INVALID_SOURCE_PAGE","页码超出范围。");
  byte[] png=preview.render(storage.read(r.get("object_key").toString()),page);requireReadable(source(id,owner,false));
  return ResponseEntity.ok().header("Content-Type","image/png").header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox; default-src 'none'").body(png);
 }
 @DeleteMapping("/api/documents/{id}") public ResponseEntity<DeletionService.Result> delete(@PathVariable UUID id,HttpServletRequest request){AuthController.sameOrigin(request);return ResponseEntity.accepted().header("Cache-Control","no-store").body(deletion.delete(id,sessions.requireWrite(request).user().id()));}
}
