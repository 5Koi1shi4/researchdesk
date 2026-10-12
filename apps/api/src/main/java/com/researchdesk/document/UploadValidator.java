package com.researchdesk.document;

import com.researchdesk.identity.ApiFailure;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class UploadValidator {
 public record Validated(Path path,String title,String digest,String mediaType,long size,Integer pages) implements AutoCloseable {
  public void close() throws IOException {Files.deleteIfExists(path);}
 }
 private final long maxBytes;private final int maxPages;
 public UploadValidator(@Value("${MAX_FILE_BYTES:20971520}") long bytes,@Value("${MAX_PDF_PAGES:200}") int pages) {
  if(bytes<1||bytes>20971520||pages<1||pages>200)throw new IllegalArgumentException("Upload limits may only tighten defaults");
  maxBytes=bytes;maxPages=pages;
 }
 public Validated validate(MultipartFile file) {
  Path path=null;
  try {
   String title=Optional.ofNullable(file.getOriginalFilename()).orElse("").replace('\\','/');title=title.substring(title.lastIndexOf('/')+1).strip();
   if(title.isEmpty()||title.length()>255)throw fail("INVALID_DOCUMENT_TYPE","请选择 PDF 或 Markdown 文件。");
   String lower=title.toLowerCase(Locale.ROOT);boolean pdf=lower.endsWith(".pdf"),markdown=lower.endsWith(".md")||lower.endsWith(".markdown");
   if(!pdf&&!markdown)throw fail("INVALID_DOCUMENT_TYPE","仅支持含文字的 PDF 和 UTF-8 Markdown。");
   if(file.getSize()>maxBytes)throw oversized();
   path=Files.createTempFile("researchdesk-upload-",".tmp");var sha=MessageDigest.getInstance("SHA-256");long total=0;
   try(var input=file.getInputStream();var output=Files.newOutputStream(path)) {
    byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){total+=n;if(total>maxBytes)throw oversized();sha.update(buffer,0,n);output.write(buffer,0,n);}
   }
   if(total==0)throw fail("DOCUMENT_NO_TEXT","资料没有可提取的文字，请选择含文字的文件。");
   byte[] header=new byte[5];try(var in=Files.newInputStream(path)){in.read(header);}
   boolean pdfMagic=Arrays.equals(header,"%PDF-".getBytes(StandardCharsets.US_ASCII));
   if(pdf!=pdfMagic)throw fail("INVALID_DOCUMENT_TYPE","文件内容与扩展名不匹配，请选择原始 PDF 或 Markdown。");
   Integer count=null;
   if(pdf) {
    count=inspectPdf(path);
   } else {
    var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    boolean text=false;
    try(var reader=new InputStreamReader(Files.newInputStream(path),decoder)){char[] chars=new char[8192];int n;while((n=reader.read(chars))!=-1)for(int i=0;i<n;i++){char c=chars[i];if(c==0||Character.isISOControl(c)&&c!='\r'&&c!='\n'&&c!='\t')throw fail("INVALID_DOCUMENT_TYPE","Markdown 包含二进制内容，请上传文本文件。");if(!Character.isWhitespace(c)&&c!='\ufeff')text=true;}}
    catch(CharacterCodingException e){throw fail("INVALID_UTF8","Markdown 必须使用 UTF-8 编码，请转换编码后重新上传。");}
    if(!text)throw fail("DOCUMENT_NO_TEXT","资料没有可提取的文字，请选择含文字的文件。");
   }
   return new Validated(path,title,HexFormat.of().formatHex(sha.digest()),pdf?"application/pdf":"text/markdown",total,count);
  }catch(ApiFailure e){delete(path);throw e;}catch(Exception e){delete(path);throw new ApiFailure(503,"UPLOAD_UNAVAILABLE","上传暂时不可用，请稍后重试。");}
 }
 private static final java.util.concurrent.Semaphore INSPECTORS=new java.util.concurrent.Semaphore(2);
 private int inspectPdf(Path path) throws Exception {
  if(!INSPECTORS.tryAcquire())throw new ApiFailure(503,"PDF_INSPECTOR_BUSY","资料检查繁忙，请稍后重新上传。");
  Process process=null;
  try {
   var command=new ArrayList<String>();
   command.add(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString());
   command.add("-Xmx128m");command.add("-XX:MaxMetaspaceSize=96m");command.add("-XX:ActiveProcessorCount=1");
   String classpath=System.getProperty("java.class.path");
   if(isBootJar(classpath)) {command.add("-jar");command.add(classpath);command.add("--inspect-pdf");}
   else {command.add("-cp");command.add(classpath);command.add(PdfInspector.class.getName());}
   command.add(path.toString());command.add(Integer.toString(maxPages));
   process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
   if(!process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor();throw resourceLimit();}
   if(process.exitValue()!=0)throw resourceLimit();
   String result=new String(process.getInputStream().readNBytes(2048),StandardCharsets.US_ASCII);
   int marker=result.lastIndexOf("RD_PDF:");if(marker>=0)result=result.substring(marker+7);
   if(result.startsWith("OK:"))return Integer.parseInt(result.substring(3));
   switch(result) {
    case "ENCRYPTED_PDF":throw fail(result,"不支持加密 PDF，请解密后重新上传。");
    case "PDF_PAGE_LIMIT":throw fail(result,"PDF 页数超出限制，请拆分后上传。");
    case "DOCUMENT_NO_TEXT":throw fail(result,"PDF 没有可提取的文字，请选择文字版 PDF；不支持扫描件 OCR。");
    case "PDF_RESOURCE_LIMIT":throw resourceLimit();
    default:throw fail("INVALID_PDF","PDF 文件无法读取，请检查文件后重新上传。");
   }
  }finally{if(process!=null&&process.isAlive())process.destroyForcibly();INSPECTORS.release();}
 }
 private static boolean isBootJar(String path) { if(!path.endsWith(".jar")||path.contains(java.io.File.pathSeparator))return false; try(var jar=new java.util.jar.JarFile(path)){return "com.researchdesk.ResearchDeskApplication".equals(jar.getManifest().getMainAttributes().getValue("Start-Class"));}catch(IOException e){return false;} }
 private static ApiFailure resourceLimit(){return fail("PDF_RESOURCE_LIMIT","PDF 结构过于复杂或解压内容超出限制，请简化或拆分文件后重新上传。");}
 private static void delete(Path p){if(p!=null)try{Files.deleteIfExists(p);}catch(IOException ignored){}}
 private static ApiFailure fail(String code,String message){return new ApiFailure(400,code,message);}
 static ApiFailure oversized(){return new ApiFailure(413,"UPLOAD_TOO_LARGE","单文件不能超过配置限制（最大 20 MiB），请拆分后上传。");}
}
