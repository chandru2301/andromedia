package com.andromedia.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SymbolIdGeneratorTest {

  @Test
  void generatesDeterministicJavaAndPythonIds() {
    assertEquals("java:com.foo.AnomalyEvent", SymbolIdGenerator.javaType("com.foo.AnomalyEvent"));
    assertEquals(
        "java:com.foo.KafkaConsumer#onMessage",
        SymbolIdGenerator.javaMethod("com.foo.KafkaConsumer", "onMessage"));
    assertEquals(
        "python:module.process_event", SymbolIdGenerator.pythonFunction("module", "process_event"));
    assertEquals(
        "python:module.AnomalyHandler.handle",
        SymbolIdGenerator.pythonClassMethod("module", "AnomalyHandler", "handle"));
  }
}
