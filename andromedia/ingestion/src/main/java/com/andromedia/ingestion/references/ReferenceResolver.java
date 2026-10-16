package com.andromedia.ingestion.references;

import com.andromedia.common.CodeSymbol;
import com.andromedia.common.ProjectSymbolRegistry;
import java.util.List;

public interface ReferenceResolver {

  List<String> resolveReferences(
      String chunkContent,
      int startLine,
      int endLine,
      List<String> imports,
      String packageName,
      List<CodeSymbol> localSymbols,
      ProjectSymbolRegistry registry);
}
