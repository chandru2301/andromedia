package com.andromedia.common;

public final class SymbolIdGenerator {

  private SymbolIdGenerator() {}

  public static String javaType(String qualifiedName) {
    return "java:" + qualifiedName;
  }

  public static String javaMethod(String qualifiedClassName, String methodName) {
    return "java:" + qualifiedClassName + "#" + methodName;
  }

  public static String javaField(String qualifiedClassName, String fieldName) {
    return "java:" + qualifiedClassName + "#" + fieldName;
  }

  public static String pythonModule(String moduleName) {
    return "python:" + moduleName;
  }

  public static String pythonFunction(String moduleName, String functionName) {
    return "python:" + moduleName + "." + functionName;
  }

  public static String pythonClass(String moduleName, String className) {
    return "python:" + moduleName + "." + className;
  }

  public static String pythonClassMethod(String moduleName, String className, String methodName) {
    return "python:" + moduleName + "." + className + "." + methodName;
  }

  /**
   * Generic, language-tagged IDs for file-scoped languages (TypeScript/JavaScript and future
   * tree-sitter grammars). The language becomes the namespace prefix so these never collide with
   * the hand-written {@code java:}/{@code python:} forms above.
   */
  public static String module(String language, String moduleName) {
    return prefix(language) + moduleName;
  }

  public static String type(String language, String moduleName, String typeName) {
    return prefix(language) + moduleName + "." + typeName;
  }

  /** Module-level function: {@code <lang>:<module>#<function>}. */
  public static String function(String language, String moduleName, String functionName) {
    return prefix(language) + moduleName + "#" + functionName;
  }

  /**
   * Method on a class-like type: {@code <lang>:<module>.<Class>#<method>}. Falls back to the
   * module-level function form when no enclosing type was resolved.
   */
  public static String method(
      String language, String moduleName, String containerName, String methodName) {
    if (containerName == null || containerName.isBlank()) {
      return function(language, moduleName, methodName);
    }
    return prefix(language) + moduleName + "." + containerName + "#" + methodName;
  }

  private static String prefix(String language) {
    String normalized =
        language == null || language.isBlank() ? "unknown" : language.trim().toLowerCase();
    return normalized + ":";
  }
}
