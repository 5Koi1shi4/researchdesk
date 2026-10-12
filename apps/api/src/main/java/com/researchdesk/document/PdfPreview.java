package com.researchdesk.document;

import com.researchdesk.identity.ApiFailure;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Runs PDFBox in a bounded child JVM. PDF scripts, links and remote resources are never executed. */
@Component
public class PdfPreview {
 private static final Semaphore SLOTS=new Semaphore(2);
 public byte[] render(byte[] bytes,int page){
  if(page<1||page>200)throw new ApiFailure(400,"INVALID_SOURCE_PAGE","页码超出范围。");
  if(!SLOTS.tryAcquire())throw failure();
  Path input=null,output=null;Process process=null;
  try{
   input=Files.createTempFile("rd-preview-",".pdf");output=Files.createTempFile("rd-preview-",".png");Files.write(input,bytes);
   var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString());
   command.addAll(List.of("-Xmx128m","-XX:MaxMetaspaceSize=96m","-XX:ActiveProcessorCount=1","-Djava.awt.headless=true"));
   String cp=System.getProperty("java.class.path");
   if(cp.endsWith(".jar")&&!cp.contains(java.io.File.pathSeparator)){command.addAll(List.of("-jar",cp,"--preview-pdf"));}
   else command.addAll(List.of("-cp",cp,PdfPreview.class.getName()));
   command.addAll(List.of(input.toString(),output.toString(),Integer.toString(page)));
   process=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
   if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor();throw failure();}
   if(process.exitValue()==2)throw new ApiFailure(400,"INVALID_SOURCE_PAGE","页码超出范围。");
   if(process.exitValue()!=0||Files.size(output)>10*1024*1024||Files.size(output)==0)throw failure();
   return Files.readAllBytes(output);
  }catch(ApiFailure e){throw e;}catch(Exception e){throw failure();}
  finally{if(process!=null&&process.isAlive())process.destroyForcibly();for(Path p:new Path[]{input,output})if(p!=null)try{Files.deleteIfExists(p);}catch(java.io.IOException ignored){}SLOTS.release();}
 }
 private ApiFailure failure(){return new ApiFailure(503,"SOURCE_PREVIEW_UNAVAILABLE","预览暂时不可用，请稍后重试。");}
 public static void main(String[] args){
  try(var doc=org.apache.pdfbox.Loader.loadPDF(Path.of(args[0]).toFile())){
   int page=Integer.parseInt(args[2])-1;if(page<0||page>=doc.getNumberOfPages()){System.exit(2);return;}
   var box=doc.getPage(page).getCropBox();float largest=Math.max(box.getWidth(),box.getHeight());
   if(!Float.isFinite(largest)||largest<=0)throw new IllegalArgumentException();
   float scale=Math.min(1.5f,1599f/largest);
   var renderer=new org.apache.pdfbox.rendering.PDFRenderer(doc);renderer.setSubsamplingAllowed(true);
   var image=renderer.renderImage(page,scale,org.apache.pdfbox.rendering.ImageType.RGB);
   if(image.getWidth()>1600||image.getHeight()>1600)throw new IllegalArgumentException();
   javax.imageio.ImageIO.write(image,"png",Path.of(args[1]).toFile());
  }catch(Throwable e){System.exit(1);}
 }
}
