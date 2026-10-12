package com.researchdesk.document;

import java.nio.file.Path;
public interface ObjectStorage {
 void put(String key,Path file,String mediaType);
 void delete(String key);
}
