package com.bar;

import com.foo.AnomalyEvent;

public class Consumer {
  public void handle(AnomalyEvent event) {
    event.getId();
  }
}
