/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.repsy.libs.storage.gateway.s3;

import io.repsy.libs.storage.core.contract.AbstractStorageStrategyContractTest;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Runs the contract against Silo, the MinIO fork the case study pins (ADR-003): minio/minio is no
 * longer on Docker Hub. Each test gets its own key prefix in one bucket.
 */
@Testcontainers
@DisplayName("S3StorageStrategy keeps the StorageStrategy contract")
class S3StorageStrategyContractIT extends AbstractStorageStrategyContractTest {

  static final String IMAGE =
      "pgsty/silo@sha256:2636fc4155d9a7e41ff58cdb65ddd13ba9304e67184e13c25bc5cb34016269d7";

  static final String BUCKET = "contract";

  @Container
  static final MinIOContainer S3 =
      new MinIOContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("minio/minio"));

  static S3Client client;

  String testPrefix;

  @BeforeAll
  static void createClient() {
    client =
        S3ClientFactory.create(
            new S3ClientFactory.Settings(
                S3.getS3URL(),
                "us-east-1",
                S3.getUserName(),
                S3.getPassword(),
                true,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                16));
    S3ClientFactory.ensureBucket(client, BUCKET);
  }

  @AfterAll
  static void closeClient() {
    client.close();
  }

  @BeforeEach
  void newPrefix() {
    this.testPrefix = "t-" + UUID.randomUUID();
    this.strategy = this.createStrategy(Duration.ofDays(7));
  }

  @Override
  protected StorageStrategy createStrategy(final Duration trashRetention) {
    final String prefix = this.testPrefix == null ? "unset" : this.testPrefix;
    return new S3StorageStrategy(
        client,
        BUCKET,
        prefix + "/maven",
        prefix + "/trash/maven",
        trashRetention,
        Clock.systemDefaultZone());
  }
}
