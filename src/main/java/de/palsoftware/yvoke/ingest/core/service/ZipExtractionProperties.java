package de.palsoftware.yvoke.ingest.core.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix="app.ingest.zip")public record ZipExtractionProperties(int maxEntries,long maxEntryBytes,long maxTotalBytes,int maxCompressionRatio){

public static final int DEFAULT_MAX_ENTRIES=50_000;public static final long DEFAULT_MAX_ENTRY_BYTES=50L*1024L*1024L;public static final long DEFAULT_MAX_TOTAL_BYTES=500L*1024L*1024L;public static final int DEFAULT_MAX_COMPRESSION_RATIO=100;

@ConstructorBinding public ZipExtractionProperties{if(maxEntries<=0){maxEntries=DEFAULT_MAX_ENTRIES;}if(maxEntryBytes<=0){maxEntryBytes=DEFAULT_MAX_ENTRY_BYTES;}if(maxTotalBytes<=0){maxTotalBytes=DEFAULT_MAX_TOTAL_BYTES;}if(maxCompressionRatio<=0){maxCompressionRatio=DEFAULT_MAX_COMPRESSION_RATIO;}}

public static ZipExtractionProperties defaults(){return new ZipExtractionProperties(DEFAULT_MAX_ENTRIES,DEFAULT_MAX_ENTRY_BYTES,DEFAULT_MAX_TOTAL_BYTES,DEFAULT_MAX_COMPRESSION_RATIO);}}
