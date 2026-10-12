package com.researchdesk.research;
import java.util.*; import java.math.BigDecimal; import java.time.LocalDate;
import org.springframework.stereotype.Service; import org.springframework.beans.factory.annotation.Value; import org.springframework.jdbc.core.JdbcTemplate;
import com.researchdesk.project.ProjectService;
/** One immutable, content-free snapshot per terminal run. Missing provider data stays unknown. */
@Service
public class UsageService {
 public record Prices(String model,String input,String output,String date,String currency) {}
 public record UsageRecord(String runId,String kind,String state,String mode,String provider,String model,Long inputTokens,Long outputTokens,Long totalTokens,Long durationMs,Long retrievalMs,Long ttftMs,Long queueWaitMs,BigDecimal estimatedCost,String currency,String priceDate,String createdAt) {}
 private final JdbcTemplate db; private final ProjectService projects; private final Prices prices;
 public UsageService(JdbcTemplate db,ProjectService projects,@Value("${USAGE_PRICE_MODEL:}") String model,@Value("${USAGE_INPUT_PRICE_PER_MILLION:}") String input,@Value("${USAGE_OUTPUT_PRICE_PER_MILLION:}") String output,@Value("${USAGE_PRICE_DATE:}") String date,@Value("${USAGE_PRICE_CURRENCY:}") String currency){this.db=db;this.projects=projects;this.prices=new Prices(model,input,output,date,currency);}
 static Long token(Object value){if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long))return null;long n=((Number)value).longValue();return n>=0&&n<=9007199254740991L?n:null;}
 static String label(Object value,int max){return value instanceof String s&&!s.isBlank()&&s.length()<=max&&!s.chars().anyMatch(Character::isISOControl)?s:null;}
 static BigDecimal estimate(Map<String,Object> usage,Prices prices){
  try{
   Long input=token(usage.get("inputTokens")),output=token(usage.get("outputTokens")),total=token(usage.get("totalTokens"));
   if(!"REAL".equals(usage.get("mode"))||!"openai-compatible".equals(usage.get("provider"))||prices.model().isBlank()||!prices.model().equals(usage.get("model"))||input==null||output==null||total==null||total!=input+output||!prices.currency().matches("[A-Z]{3}"))return null;
   LocalDate.parse(prices.date());BigDecimal in=new BigDecimal(prices.input()),out=new BigDecimal(prices.output());
   if(in.signum()<0||out.signum()<0||in.compareTo(new BigDecimal("1000000"))>0||out.compareTo(new BigDecimal("1000000"))>0)return null;
   return in.multiply(BigDecimal.valueOf(input)).add(out.multiply(BigDecimal.valueOf(output))).divide(BigDecimal.valueOf(1000000)).setScale(12,java.math.RoundingMode.HALF_UP);
  }catch(RuntimeException invalid){return null;}
 }
 void record(UUID run,String mode,Map<String,Object> raw){
  var usage=new HashMap<String,Object>();if(raw!=null)usage.putAll(raw);
  // Deployment/run mode is authoritative; conflicting metadata cannot produce billing estimates.
  if(!mode.equals(usage.get("mode")))usage.clear();usage.put("mode",mode);
  BigDecimal cost=estimate(usage,prices);
  db.update("""
   INSERT INTO business.usage_records(run_id,provider,model,mode,input_tokens,output_tokens,total_tokens,duration_ms,retrieval_ms,ttft_ms,queue_wait_ms,estimated_cost,currency,price_date,input_price_per_million,output_price_per_million)
   SELECT id,?,?,?,?,?,?,COALESCE(?,CASE WHEN deadline IS NULL THEN NULL ELSE GREATEST(0,(EXTRACT(EPOCH FROM (updated_at-(deadline-(CASE WHEN kind='ANSWER' THEN 120 ELSE 300 END)*interval '1 second')))*1000)::bigint) END),?,?,
   CASE WHEN deadline IS NULL THEN NULL ELSE GREATEST(0,(EXTRACT(EPOCH FROM (deadline-(CASE WHEN kind='ANSWER' THEN 120 ELSE 300 END)*interval '1 second'-created_at))*1000)::bigint) END,?,?,?::date,?,?
   FROM business.generation_runs WHERE id=? ON CONFLICT(run_id) DO NOTHING
   """,label(usage.get("provider"),80),label(usage.get("model"),200),mode,token(usage.get("inputTokens")),token(usage.get("outputTokens")),token(usage.get("totalTokens")),token(usage.get("durationMs")),token(usage.get("retrievalMs")),token(usage.get("ttftMs")),cost,cost==null?null:prices.currency(),cost==null?null:prices.date(),cost==null?null:new BigDecimal(prices.input()),cost==null?null:new BigDecimal(prices.output()),run);
 }
 public List<UsageRecord> list(UUID owner,UUID project){
  projects.findByIdAndOwnerId(project,owner);
  return db.query("""
   SELECT u.*,r.kind,r.state FROM business.usage_records u JOIN business.generation_runs r ON r.id=u.run_id
   JOIN business.projects p ON p.id=r.project_id WHERE p.id=? AND p.owner_id=? ORDER BY u.created_at DESC,u.run_id LIMIT 100
   """,(r,n)->new UsageRecord(r.getString("run_id"),r.getString("kind"),r.getString("state"),r.getString("mode"),r.getString("provider"),r.getString("model"),r.getObject("input_tokens",Long.class),r.getObject("output_tokens",Long.class),r.getObject("total_tokens",Long.class),r.getObject("duration_ms",Long.class),r.getObject("retrieval_ms",Long.class),r.getObject("ttft_ms",Long.class),r.getObject("queue_wait_ms",Long.class),r.getBigDecimal("estimated_cost"),r.getString("currency"),r.getString("price_date"),r.getTimestamp("created_at").toInstant().toString()),project,owner);
 }
}
