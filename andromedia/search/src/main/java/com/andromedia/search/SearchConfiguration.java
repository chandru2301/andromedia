package com.andromedia.search;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackageClasses = LuceneSearchService.class)
@EnableConfigurationProperties({
  RankingHeuristicsProperties.class,
  ZohoQualificationProperties.class
})
public class SearchConfiguration {}
