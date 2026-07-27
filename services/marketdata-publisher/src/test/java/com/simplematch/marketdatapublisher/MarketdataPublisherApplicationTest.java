package com.simplematch.marketdatapublisher;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.marketdatapublisher.publication.MarketSnapshotApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
  "simplematch.postgres.dsn=jdbc:h2:mem:marketdata-publisher-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS marketdata_publisher\\;SET SCHEMA marketdata_publisher"
})
class MarketdataPublisherApplicationTest {
  @Autowired private MarketSnapshotApplicationService publicationService;

  @Test
  void startsWithoutRuntimeConsumers() {
    assertThat(publicationService).isNotNull();
  }
}
