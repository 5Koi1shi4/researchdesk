package com.researchdesk.report;

import static org.assertj.core.api.Assertions.*;

import com.researchdesk.identity.ApiFailure;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReportMarkerTest {
  @Test
  void stableMarkersDeduplicateWithoutRenumbering() {
    String id = UUID.randomUUID().toString();
    assertThat(ReportService.markers("中文 [[cite:" + id + "]] text [[cite:" + id + "]]"))
        .containsExactly(id);
    assertThat(ReportService.markers("deleted all")).isEmpty();
  }

  @Test
  void malformedMarkersAndOversizedDocumentsAreRejected() {
    assertThatThrownBy(() -> ReportService.markers("[[cite:wrong]]"))
        .isInstanceOf(ApiFailure.class);
    assertThatThrownBy(() -> ReportService.markers("a".repeat(200001)))
        .isInstanceOf(ApiFailure.class);
    assertThatThrownBy(() -> ReportService.markers("[[cite:unfinished"))
        .isInstanceOf(ApiFailure.class);
  }
}
