package com.researchdesk.research;
import com.researchdesk.Contracts.*; import java.util.*; import org.junit.jupiter.api.Test; import static org.assertj.core.api.Assertions.*;
class RunEventValidatorTest {
 @Test void rejectsMismatchedTerminalAndExtraPayloadFields(){assertThatThrownBy(()->RunEventValidator.validate(EventType.COMPLETED,Map.of("state","CANCELLED"))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->RunEventValidator.validate(EventType.DELTA,Map.of("text","x","secret","x"))).isInstanceOf(IllegalArgumentException.class);}
 @Test void acceptsCanonicalDeltaAndRejectsEmpty(){RunEventValidator.validate(EventType.DELTA,Map.of("text","hello"));assertThatThrownBy(()->RunEventValidator.validate(EventType.DELTA,Map.of("text",""))).isInstanceOf(IllegalArgumentException.class);}
 @Test void multibyteHistoryUsesConservativeUtf8Budget(){var messages=List.of(Map.of("role","user","content","😀".repeat(3000)));assertThat(GenerationService.boundedHistory(messages)).isEmpty();}
 @Test void trimsHistoryByRecentMessagesAndConservativeTokenBudget(){var messages=new ArrayList<Map<String,String>>();for(int i=0;i<20;i++)messages.add(Map.of("role","user","content","x".repeat(1000)));var result=GenerationService.boundedHistory(messages);assertThat(result).hasSize(8);}
}
