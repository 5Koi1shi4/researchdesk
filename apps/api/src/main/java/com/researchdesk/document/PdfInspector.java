package com.researchdesk.document;

import java.io.*;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/** One-shot metadata/text-presence check in a separate bounded JVM. Never starts Spring. */
public final class PdfInspector {
 public static void main(String[] args) {
  PrintStream resultOutput=System.out;
  String result;
  try {
   // Dependency logging must not fill the result pipe or obscure the small result protocol.
   System.setOut(System.err);
   result=inspect(args);
  }finally{System.setOut(resultOutput);}
  resultOutput.print("RD_PDF:"+result);
 }
 private static String inspect(String[] args) {
  try(var doc=Loader.loadPDF(Path.of(args[0]).toFile())) {
   if(doc.isEncrypted())return "ENCRYPTED_PDF";
   int pages=doc.getNumberOfPages();if(pages>Integer.parseInt(args[1]))return "PDF_PAGE_LIMIT";
   long decoded=0;byte[] buffer=new byte[8192];
   for(COSObjectKey key:new java.util.ArrayList<>(doc.getDocument().getXrefTable().keySet())) {
    COSObject object=doc.getDocument().getObjectFromPool(key);
    if(object.getObject() instanceof COSStream stream)try(var in=stream.createInputStream()){
     int n;while((n=in.read(buffer))!=-1){decoded+=n;if(decoded>32L*1024*1024)return "PDF_RESOURCE_LIMIT";}
    }
   }
   var writer=new Presence();new PDFTextStripper().writeText(doc,writer);
   return writer.present?"OK:"+pages:"DOCUMENT_NO_TEXT";
  }catch(InvalidPasswordException e){return "ENCRYPTED_PDF";}
  catch(OutOfMemoryError e){return "PDF_RESOURCE_LIMIT";}
  catch(Exception e){return "INVALID_PDF";}
 }
 private static class Presence extends Writer {
  boolean present;
  public void write(char[] text,int offset,int length){for(int i=offset;i<offset+length;i++)if(!Character.isWhitespace(text[i]))present=true;}
  public void flush(){}public void close(){}
 }
}
