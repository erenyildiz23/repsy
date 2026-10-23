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

import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/** Builds the {@link S3Client} of the S3 storage strategy. */
public final class S3ClientFactory {

  private S3ClientFactory() {}

  /**
   * The connection settings of the store.
   *
   * @param endpoint the store's URL, for example {@code http://s3:9000}; AWS when empty
   * @param pathStyle whether the bucket is a path segment ({@code /bucket/key}), as MinIO and its
   *     forks need, instead of a host name
   * @param connectTimeout how long to wait for a TCP connection
   * @param socketTimeout how long a read may wait for the next byte: a hung store fails the request
   *     after this instead of holding the request thread forever
   * @param maxConnections the size of the HTTP connection pool
   */
  public record Settings(
      String endpoint,
      String region,
      String accessKey,
      String secretKey,
      boolean pathStyle,
      Duration connectTimeout,
      Duration socketTimeout,
      int maxConnections) {}

  public static S3Client create(final Settings settings) {
    final var builder =
        S3Client.builder()
            .region(Region.of(settings.region()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
            .forcePathStyle(settings.pathStyle())
            // S3 compatible stores differ in which of the newer default checksums they accept;
            // send and check them only where the operation requires it.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .httpClientBuilder(
                ApacheHttpClient.builder()
                    .connectionTimeout(settings.connectTimeout())
                    .socketTimeout(settings.socketTimeout())
                    .maxConnections(settings.maxConnections()));

    if (!settings.endpoint().isBlank()) {
      builder.endpointOverride(URI.create(settings.endpoint()));
    }

    return builder.build();
  }

  /** Creates the bucket when it does not exist yet. */
  public static void ensureBucket(final S3Client s3, final String bucket) {
    try {
      s3.headBucket(r -> r.bucket(bucket));
    } catch (final NoSuchBucketException e) {
      try {
        s3.createBucket(r -> r.bucket(bucket));
      } catch (final BucketAlreadyOwnedByYouException ignored) {
        // created concurrently by another instance
      }
    }
  }
}
