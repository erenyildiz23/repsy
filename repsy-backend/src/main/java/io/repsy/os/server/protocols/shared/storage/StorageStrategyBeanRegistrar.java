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
package io.repsy.os.server.protocols.shared.storage;

import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.libs.storage.gateway.cache.CachingStorageStrategy;
import io.repsy.libs.storage.gateway.cache.RedisStorageCache;
import io.repsy.libs.storage.gateway.filesystem.services.FileSystemStorageStrategy;
import io.repsy.libs.storage.gateway.s3.S3ClientFactory;
import io.repsy.libs.storage.gateway.s3.S3StorageStrategy;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Registers one {@link StorageStrategy} bean per {@link RepoType}, named by {@link
 * #beanName(RepoType)}, in place of a configuration class and a properties class per protocol.
 *
 * <p>{@code os.app.storage.backend} ({@code STORAGE_BACKEND}) picks the implementation for all of
 * them: {@code file-system} (the default) or {@code s3} (ADR-001, case study).
 *
 * <p>File system: the locations are read from {@code os.app.storage.file-system.<type>.base-path}
 * and {@code .trash-path}, where {@code <type>} is the lower-case {@link RepoType} name ({@code
 * maven}, {@code npm}, {@code golang}...). These keys, and the {@code STORAGE_BASE_PATH}
 * placeholder behind their defaults in {@code application.yml}, are a user contract and do not
 * change.
 *
 * <p>S3: one {@link S3Client} (bean {@code osS3Client}) is shared by the strategies; each keeps its
 * objects under {@code <type>/} and its trash under {@code trash/<type>/} of the bucket that {@link
 * S3StorageProperties} names, which is created on first use when it does not exist.
 *
 * <p>{@code os.app.storage.cache.type} ({@code STORAGE_CACHE}) = {@code redis} puts every strategy,
 * of either backend, behind a {@link CachingStorageStrategy} that keeps the small metadata files in
 * Redis (ADR-005); {@code none}, the default, leaves the strategies as they are.
 */
public class StorageStrategyBeanRegistrar implements BeanRegistrar {
  static final String KEY_PREFIX = "os.app.storage.file-system.";
  static final String BACKEND_KEY = "os.app.storage.backend";
  static final String S3_KEY = "os.app.storage.s3";
  static final String S3_CLIENT_BEAN = "osS3Client";
  static final String CACHE_KEY = "os.app.storage.cache";
  static final String CACHE_BEAN = "osStorageCache";

  private static final EnumMap<RepoType, String> BEAN_NAMES = new EnumMap<>(RepoType.class);

  static {
    BEAN_NAMES.put(RepoType.MAVEN, "osStorageStrategyMaven");
    BEAN_NAMES.put(RepoType.NPM, "osStorageStrategyNpm");
    BEAN_NAMES.put(RepoType.PYPI, "osStorageStrategyPypi");
    BEAN_NAMES.put(RepoType.DOCKER, "osStorageStrategyDocker");
    BEAN_NAMES.put(RepoType.CARGO, "osStorageStrategyCargo");
    BEAN_NAMES.put(RepoType.GOLANG, "osStorageStrategyGolang");
    BEAN_NAMES.put(RepoType.HELM, "osStorageStrategyHelm");
    BEAN_NAMES.put(RepoType.NUGET, "osStorageStrategyNuGet");
    BEAN_NAMES.put(RepoType.RUBY, "osStorageStrategyRuby");
  }

  /** The bean name of the strategy of {@code repoType}; tests spy the strategy by this name. */
  public static String beanName(final RepoType repoType) {
    return Objects.requireNonNull(BEAN_NAMES.get(repoType));
  }

  /** The configuration key prefix of {@code repoType}, without a trailing dot. */
  public static String keyPrefix(final RepoType repoType) {
    return KEY_PREFIX + repoType.name().toLowerCase(Locale.ROOT);
  }

  @Override
  public void register(final BeanRegistry registry, final Environment env) {
    final var binder = Binder.get(env);
    final var backend = binder.bind(BACKEND_KEY, String.class).orElse("file-system");

    final var cache = binder.bindOrCreate(CACHE_KEY, StorageCacheProperties.class);
    this.registerCache(registry, cache);

    switch (backend) {
      case "file-system" -> this.registerFileSystem(registry, binder, cache);
      case "s3" -> this.registerS3(registry, binder, cache);
      default ->
          throw new IllegalStateException(
              BACKEND_KEY + " (STORAGE_BACKEND) must be file-system or s3, but was " + backend);
    }
  }

  /** Registers the Redis cache when {@code os.app.storage.cache.type} is {@code redis}. */
  private void registerCache(final BeanRegistry registry, final StorageCacheProperties cache) {
    switch (cache.type()) {
      case "none" -> {}
      case "redis" ->
          registry.registerBean(
              CACHE_BEAN,
              RedisStorageCache.class,
              spec ->
                  spec.supplier(
                      context ->
                          new RedisStorageCache(
                              cache.redisUrl(), cache.ttl(), cache.commandTimeout())));
      default ->
          throw new IllegalStateException(
              CACHE_KEY + ".type (STORAGE_CACHE) must be none or redis, but was " + cache.type());
    }
  }

  /** The strategy itself, or the strategy behind the metadata cache when one is configured. */
  private static StorageStrategy cached(
      final BeanRegistry.SupplierContext context,
      final StorageCacheProperties cache,
      final RepoType repoType,
      final StorageStrategy strategy) {
    if (!"redis".equals(cache.type())) {
      return strategy;
    }

    return new CachingStorageStrategy(
        strategy,
        context.bean(CACHE_BEAN, RedisStorageCache.class),
        repoType.name().toLowerCase(Locale.ROOT),
        cache.fileNames(),
        cache.maxBytes().toBytes());
  }

  private void registerFileSystem(
      final BeanRegistry registry, final Binder binder, final StorageCacheProperties cache) {
    for (final var repoType : RepoType.values()) {
      registry.registerBean(
          beanName(repoType),
          StorageStrategy.class,
          spec ->
              spec.supplier(
                  context -> {
                    final var key = keyPrefix(repoType);
                    final var paths =
                        binder
                            .bind(key, StoragePaths.class)
                            .orElseThrow(
                                () ->
                                    new IllegalStateException(
                                        key + ".base-path and .trash-path are not configured"));
                    final var trash = context.bean(StorageTrashProperties.class);

                    return cached(
                        context,
                        cache,
                        repoType,
                        new FileSystemStorageStrategy(
                            paths.basePath(), paths.trashPath(), trash.trashRetention()));
                  }));
    }
  }

  private void registerS3(
      final BeanRegistry registry, final Binder binder, final StorageCacheProperties cache) {
    final var properties = binder.bindOrCreate(S3_KEY, S3StorageProperties.class);

    registry.registerBean(
        S3_CLIENT_BEAN,
        S3Client.class,
        spec ->
            spec.supplier(
                context -> {
                  final var client =
                      S3ClientFactory.create(
                          new S3ClientFactory.Settings(
                              properties.endpoint(),
                              properties.region(),
                              properties.accessKey(),
                              properties.secretKey(),
                              properties.pathStyle(),
                              properties.connectTimeout(),
                              properties.socketTimeout(),
                              properties.maxConnections()));
                  S3ClientFactory.ensureBucket(client, properties.bucket());
                  return client;
                }));

    for (final var repoType : RepoType.values()) {
      final var type = repoType.name().toLowerCase(Locale.ROOT);

      registry.registerBean(
          beanName(repoType),
          StorageStrategy.class,
          spec ->
              spec.supplier(
                  context ->
                      cached(
                          context,
                          cache,
                          repoType,
                          new S3StorageStrategy(
                              context.bean(S3_CLIENT_BEAN, S3Client.class),
                              properties.bucket(),
                              type,
                              "trash/" + type,
                              context.bean(StorageTrashProperties.class).trashRetention(),
                              Clock.systemDefaultZone()))));
    }
  }

  /** The two locations of one repo type, as the configuration names them. */
  record StoragePaths(String basePath, String trashPath) {}
}
