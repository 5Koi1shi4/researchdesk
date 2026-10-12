package com.researchdesk.document;
import java.io.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PdfPreviewTest {
 @Test void rendersPngWithPixelBudgetAndRejectsInvalidPages()throws Exception {
  byte[] pdf;try(var d=new PDDocument();var out=new ByteArrayOutputStream()){d.addPage(new PDPage(new PDRectangle(100000,100000)));d.save(out);pdf=out.toByteArray();}
  var renderer=new PdfPreview();byte[] png=renderer.render(pdf,1);var image=javax.imageio.ImageIO.read(new ByteArrayInputStream(png));assertThat(image.getWidth()).isLessThanOrEqualTo(1600);assertThat(image.getHeight()).isLessThanOrEqualTo(1600);
  assertThatThrownBy(()->renderer.render(pdf,2)).isInstanceOf(com.researchdesk.identity.ApiFailure.class);
 }
}
