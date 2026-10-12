package com.researchdesk.document;
import java.io.*;
import java.util.Arrays;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import com.researchdesk.identity.ApiFailure;
import static org.assertj.core.api.Assertions.*;

class PdfResourceTest {
 @Test void acceptsTextPdfAtTwoHundredPageBoundary() throws Exception {
  byte[] bytes;
  try(var d=new PDDocument();var out=new ByteArrayOutputStream()) {
   for(int i=0;i<200;i++) {var p=new PDPage();d.addPage(p);try(var c=new PDPageContentStream(d,p)){c.beginText();c.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);c.newLineAtOffset(50,700);c.showText("Research fixture page "+i);c.endText();}}
   d.save(out);bytes=out.toByteArray();
  }
  java.nio.file.Path file=java.nio.file.Path.of("target/pdf200-fixture.pdf");java.nio.file.Files.write(file,bytes);
  try(var validated=new UploadValidator(20971520,200).validate(new MockMultipartFile("file","valid.pdf","application/pdf",bytes))) {assertThat(validated.pages()).isEqualTo(200);}
 }
 @Test void compressedContentCannotExpandPastInspectorHeapAndApiRemainsResponsive() throws Exception {
  byte[] bytes;
  try(var d=new PDDocument();var out=new ByteArrayOutputStream()) {
   var p=new PDPage();d.addPage(p);var stream=new PDStream(d);
   try(var content=stream.createOutputStream(COSName.FLATE_DECODE)){byte[] spaces=new byte[1024*1024];Arrays.fill(spaces,(byte)' ');for(int i=0;i<180;i++)content.write(spaces);}
   p.setContents(stream);d.save(out);bytes=out.toByteArray();
  }
  assertThat(bytes.length).isLessThan(1024*1024);
  long start=System.nanoTime();
  assertThatThrownBy(()->new UploadValidator(20971520,200).validate(new MockMultipartFile("file","bomb.pdf","application/pdf",bytes))).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code).isEqualTo("PDF_RESOURCE_LIMIT"));
  assertThat((System.nanoTime()-start)/1_000_000).isLessThan(15000);
 }
}
