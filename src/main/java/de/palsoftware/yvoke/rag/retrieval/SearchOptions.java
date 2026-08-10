package de.palsoftware.yvoke.rag.retrieval;

import jakarta.annotation.Nullable;
import java.util.*;

public record SearchOptions(List<String>collections,@Nullable Integer limit,Boolean semantic,Boolean fulltext,Integer offset,@Nullable Boolean rerank,List<String>tags){

// No ceiling here on purpose: that is policy, and it lives in exactly one place —
// app.retrieval.max-limit, applied by HybridSearch.searchWithId, the single choke point all
// three lane paths pass through. A second hardcoded ceiling in this record would only drift.
public SearchOptions{if((collections==null||collections.isEmpty())&&(tags==null||tags.isEmpty())){throw new IllegalArgumentException("Either collections or tags must be specified");}if(collections==null){collections=Collections.emptyList();}if(tags==null){tags=Collections.emptyList();}

// Normalize "Both" to list of default collections if it appears
if(collections.contains("Both")){List<String>normalized=new ArrayList<>();for(String col:collections){if("Both".equals(col)){normalized.add("OIM");normalized.add("OIM-DB");}else{normalized.add(col);}}collections=List.copyOf(normalized);}

if(limit!=null&&limit<1){limit=10;}if(semantic==null){semantic=true;}if(fulltext==null){fulltext=true;}if(tags==null){tags=Collections.emptyList();}if(offset==null||offset<0){offset=0;}if(rerank==null){rerank=true;}}

// Constructor for backwards compatibility with collections but no tags
public SearchOptions(List<String>collections,@Nullable Integer limit,Boolean semantic,Boolean fulltext,Integer offset,@Nullable Boolean rerank){this(collections,limit,semantic,fulltext,offset,rerank,Collections.emptyList());}

// Overloaded constructor for backward compatibility with single collection/version
public SearchOptions(String collection,@Nullable Integer limit,Boolean semantic,Boolean fulltext,@Nullable String tag,Integer offset,@Nullable Boolean rerank){this((collection==null||collection.isBlank())?Collections.emptyList():Arrays.asList(collection.split(",")),limit,semantic,fulltext,offset,rerank,(tag==null||tag.isBlank())?Collections.emptyList():Arrays.asList(tag.split(",")));}

// Overloaded constructor for backward compatibility without rerank
public SearchOptions(String collection,@Nullable Integer limit,Boolean semantic,Boolean fulltext,@Nullable String tag,Integer offset){this(collection,limit,semantic,fulltext,tag,offset,true);}

public static SearchOptions defaultOptions(String collection){return new SearchOptions(collection,null,true,true,null,0,true);}

public String collection(){return(collections!=null&&!collections.isEmpty())?String.join(",",collections):"";}

public String tag(){return(tags!=null&&!tags.isEmpty())?String.join(",",tags):null;}}
