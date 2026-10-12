package com.researchdesk.research;
import java.util.*; import java.math.BigDecimal; import org.junit.jupiter.api.Test; import static org.assertj.core.api.Assertions.*;
class UsageServiceTest {
 @Test void unknownOrMalformedTokensNeverBecomeZero(){assertThat(UsageService.token(null)).isNull();assertThat(UsageService.token(-1)).isNull();assertThat(UsageService.token("12")).isNull();assertThat(UsageService.token(true)).isNull();assertThat(UsageService.token(1.2)).isNull();assertThat(UsageService.token(0)).isZero();}
 @Test void estimateRequiresCompleteRealUsageAndDatedPrices(){
  var usage=Map.<String,Object>of("mode","REAL","provider","openai-compatible","model","fixture","inputTokens",1000,"outputTokens",500,"totalTokens",1500);
  var price=new UsageService.Prices("fixture","2","4","2026-10-12","USD");
  assertThat(UsageService.estimate(usage,price)).isEqualByComparingTo("0.004");
  var incomplete=new HashMap<>(usage);incomplete.remove("totalTokens");assertThat(UsageService.estimate(incomplete,price)).isNull();
  var demo=new HashMap<>(usage);demo.put("mode","DEMO");assertThat(UsageService.estimate(demo,price)).isNull();
  assertThat(UsageService.estimate(usage,new UsageService.Prices("fixture","2","4","","USD"))).isNull();
  assertThat(UsageService.estimate(usage,new UsageService.Prices("other","2","4","2026-10-12","USD"))).isNull();
 }
}
