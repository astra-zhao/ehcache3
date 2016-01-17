/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ehcache.internal.store.tiering;

import java.io.IOException;

import org.ehcache.config.EvictionVeto;
import org.ehcache.config.ResourcePool;
import org.ehcache.config.ResourcePools;
import org.ehcache.config.ResourceType;
import org.ehcache.config.StoreConfigurationImpl;
import org.ehcache.config.persistence.CacheManagerPersistenceConfiguration;
import org.ehcache.config.units.EntryUnit;
import org.ehcache.exceptions.CachePersistenceException;
import org.ehcache.expiry.Expirations;
import org.ehcache.expiry.Expiry;
import org.ehcache.internal.SystemTimeSource;
import org.ehcache.internal.TimeSource;
import org.ehcache.internal.concurrent.ConcurrentHashMap;
import org.ehcache.internal.copy.IdentityCopier;
import org.ehcache.internal.persistence.DefaultLocalPersistenceService;
import org.ehcache.internal.serialization.JavaSerializer;
import org.ehcache.internal.sizeof.DefaultSizeOfEngine;
import org.ehcache.internal.store.StoreFactory;
import org.ehcache.internal.store.StoreSPITest;
import org.ehcache.internal.store.heap.OnHeapStore;
import org.ehcache.internal.store.heap.OnHeapStoreByValueSPITest;
import org.ehcache.spi.ServiceLocator;
import org.ehcache.spi.ServiceProvider;
import org.ehcache.spi.cache.Store;
import org.ehcache.spi.cache.tiering.AuthoritativeTier;
import org.ehcache.spi.cache.tiering.CachingTier;
import org.ehcache.spi.copy.Copier;
import org.ehcache.spi.serialization.Serializer;
import org.ehcache.spi.service.FileBasedPersistenceContext;
import org.ehcache.spi.service.ServiceConfiguration;
import org.junit.After;
import org.junit.Before;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.ehcache.config.ResourcePoolsBuilder.newResourcePoolsBuilder;
import static org.ehcache.config.ResourceType.Core.DISK;

import org.ehcache.config.units.MemoryUnit;
import org.ehcache.internal.executor.OnDemandExecutionService;
import org.ehcache.internal.store.disk.OffHeapDiskStore;
import org.ehcache.internal.store.disk.OffHeapDiskStoreSPITest;
import org.ehcache.spi.service.LocalPersistenceService;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import static org.mockito.Mockito.mock;

/**
 * Test the {@link org.ehcache.internal.store.tiering.CacheStore} compliance to the
 * {@link org.ehcache.spi.cache.Store} contract.
 *
 * @author Ludovic Orban
 */

public class CacheStoreSPITest extends StoreSPITest<String, String> {

  private StoreFactory<String, String> storeFactory;
  private final CacheStore.Provider provider = new CacheStore.Provider();
  private final Map<Store<String, String>, String> createdStores = new ConcurrentHashMap<Store<String, String>, String>();
  private LocalPersistenceService persistenceService;

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();
  
  @Override
  protected StoreFactory<String, String> getStoreFactory() {
    return storeFactory;
  }

  @Before
  public void setUp() throws IOException {
    persistenceService = new DefaultLocalPersistenceService(new CacheManagerPersistenceConfiguration(folder.newFolder()));

    storeFactory = new StoreFactory<String, String>() {
      final AtomicInteger aliasCounter = new AtomicInteger();

      @Override
      public Store<String, String> newStore() {
        return newStore(null, null, Expirations.noExpiration(), SystemTimeSource.INSTANCE);
      }

      @Override
      public Store<String, String> newStoreWithCapacity(long capacity) {
        return newStore(capacity, null, Expirations.noExpiration(), SystemTimeSource.INSTANCE);
      }

      @Override
      public Store<String, String> newStoreWithExpiry(Expiry<String, String> expiry, TimeSource timeSource) {
        return newStore(null, null, expiry, timeSource);
      }

      @Override
      public Store<String, String> newStoreWithEvictionVeto(EvictionVeto<String, String> evictionVeto) {
        return newStore(null, evictionVeto, Expirations.noExpiration(), SystemTimeSource.INSTANCE);
      }
      
      private Store<String, String> newStore(Long capacity, EvictionVeto<String, String> evictionVeto, Expiry<? super String, ? super String> expiry, TimeSource timeSource) {
        Serializer<String> keySerializer = new JavaSerializer<String>(getClass().getClassLoader());
        Serializer<String> valueSerializer = new JavaSerializer<String>(getClass().getClassLoader());
        Store.Configuration<String, String> config = new StoreConfigurationImpl<String, String>(getKeyType(), getValueType(), evictionVeto, getClass().getClassLoader(), expiry, buildResourcePools(capacity), keySerializer, valueSerializer);

        final Copier defaultCopier = new IdentityCopier();
        OnHeapStore<String, String> onHeapStore = new OnHeapStore<String, String>(config, timeSource, defaultCopier, defaultCopier, new DefaultSizeOfEngine(0, 0));
        try {
          String spaceName = "alias-" + aliasCounter.getAndIncrement();
          LocalPersistenceService.PersistenceSpaceIdentifier space = persistenceService.getOrCreatePersistenceSpace(spaceName);
          FileBasedPersistenceContext persistenceContext = persistenceService.createPersistenceContextWithin(space, "store");

          ResourcePool diskPool = config.getResourcePools().getPoolForResource(ResourceType.Core.DISK);
          MemoryUnit unit = (MemoryUnit) diskPool.getUnit();

          long sizeInBytes = unit.toBytes(diskPool.getSize());
          OffHeapDiskStore<String, String> diskStore = new OffHeapDiskStore<String, String>(
                  persistenceContext,
                  new OnDemandExecutionService(), null, 1,
                  config, timeSource, sizeInBytes);
          CacheStore<String, String> cacheStore = new CacheStore<String, String>(onHeapStore, diskStore);
          provider.registerStore(cacheStore, new CachingTier.Provider() {
            @Override
            public <K, V> CachingTier<K, V> createCachingTier(final Store.Configuration<K, V> storeConfig, final ServiceConfiguration<?>... serviceConfigs) {
              throw new UnsupportedOperationException("Implement me!");
            }

            @Override
            public void releaseCachingTier(final CachingTier<?, ?> resource) {
              OnHeapStoreByValueSPITest.closeStore((OnHeapStore)resource);
            }

            @Override
            public void initCachingTier(final CachingTier<?, ?> resource) {
              // no op
            }

            @Override
            public void start(final ServiceProvider serviceProvider) {
              throw new UnsupportedOperationException("Implement me!");
            }

            @Override
            public void stop() {
              throw new UnsupportedOperationException("Implement me!");
            }
          }, new AuthoritativeTier.Provider() {
            @Override
            public <K, V> AuthoritativeTier<K, V> createAuthoritativeTier(final Store.Configuration<K, V> storeConfig, final ServiceConfiguration<?>... serviceConfigs) {
              throw new UnsupportedOperationException("Implement me!");
            }

            @Override
            public void releaseAuthoritativeTier(final AuthoritativeTier<?, ?> resource) {
              OffHeapDiskStoreSPITest.closeStore((OffHeapDiskStore<?, ?>)resource);
            }

            @Override
            public void initAuthoritativeTier(final AuthoritativeTier<?, ?> resource) {
              OffHeapDiskStoreSPITest.initStore((OffHeapDiskStore<?, ?>)resource);
            }

            @Override
            public void start(final ServiceProvider serviceProvider) {
              throw new UnsupportedOperationException("Implement me!");
            }

            @Override
            public void stop() {
              throw new UnsupportedOperationException("Implement me!");
            }
          });
          provider.initStore(cacheStore);
          createdStores.put(cacheStore, spaceName);
          return cacheStore;
        } catch (CachePersistenceException e) {
          throw new RuntimeException("Error creation persistence context", e);
        }
      }

      @Override
      public Store.ValueHolder<String> newValueHolder(final String value) {
        final long creationTime = SystemTimeSource.INSTANCE.getTimeMillis();
        return new Store.ValueHolder<String>() {

          @Override
          public String value() {
            return value;
          }

          @Override
          public long creationTime(TimeUnit unit) {
            return creationTime;
          }

          @Override
          public long expirationTime(TimeUnit unit) {
            return 0;
          }

          @Override
          public boolean isExpired(long expirationTime, TimeUnit unit) {
            return false;
          }

          @Override
          public long lastAccessTime(TimeUnit unit) {
            return 0;
          }

          @Override
          public float hitRate(long now, TimeUnit unit) {
            return 0;
          }

          @Override
          public long hits() {
            throw new UnsupportedOperationException("Implement me!");
          }

          @Override
          public long getId() {
            throw new UnsupportedOperationException("Implement me!");
          }
        };
      }

      @Override
      public Class<String> getKeyType() {
        return String.class;
      }

      @Override
      public Class<String> getValueType() {
        return String.class;
      }

      @Override
      public ServiceConfiguration<?>[] getServiceConfigurations() {
        return new ServiceConfiguration[]{new CacheStoreServiceConfiguration().cachingTierProvider(FakeCachingTierProvider.class).authoritativeTierProvider(FakeAuthoritativeTierProvider.class)};
      }

      @Override
      public String createKey(long seed) {
        return Long.toString(seed);
      }

      @Override
      public String createValue(long seed) {
        char[] chars = new char[400 * 1024];
        Arrays.fill(chars, (char) (0x1 + (seed & 0x7e)));
        return new String(chars);
      }

      @Override
      public void close(final Store<String, String> store) {
        String spaceName = createdStores.get(store);
        provider.releaseStore(store);
        try {
          persistenceService.destroyPersistenceSpace(spaceName);
        } catch (CachePersistenceException e) {
          throw new AssertionError(e);
        } finally {
          createdStores.remove(store);
        }
      }

      @Override
      public ServiceProvider getServiceProvider() {
        ServiceLocator serviceLocator = new ServiceLocator();
        serviceLocator.addService(new FakeCachingTierProvider());
        serviceLocator.addService(new FakeAuthoritativeTierProvider());
        return serviceLocator;
      }
    };
  }

  @After
  public void tearDown() throws CachePersistenceException {
    try {
      for (Map.Entry<Store<String, String>, String> entry : createdStores.entrySet()) {
        provider.releaseStore(entry.getKey());
        persistenceService.destroyPersistenceSpace(entry.getValue());
      }
    } finally {
      persistenceService.stop();
    }
  }

  private ResourcePools buildResourcePools(Comparable<Long> capacityConstraint) {
    if (capacityConstraint == null) {
      capacityConstraint = 16L;
    }
    return newResourcePoolsBuilder().heap(5, EntryUnit.ENTRIES).disk((Long)capacityConstraint, MemoryUnit.MB).build();
  }

  public static class FakeCachingTierProvider implements CachingTier.Provider {
    @Override
    public <K, V> CachingTier<K, V> createCachingTier(Store.Configuration<K, V> storeConfig, ServiceConfiguration<?>... serviceConfigs) {
      return mock(CachingTier.class);
    }

    @Override
    public void releaseCachingTier(CachingTier<?, ?> resource) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void initCachingTier(CachingTier<?, ?> resource) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void start(ServiceProvider serviceProvider) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void stop() {
      throw new UnsupportedOperationException();
    }
  }

  public static class FakeAuthoritativeTierProvider implements AuthoritativeTier.Provider {
    @Override
    public <K, V> AuthoritativeTier<K, V> createAuthoritativeTier(Store.Configuration<K, V> storeConfig, ServiceConfiguration<?>... serviceConfigs) {
      return mock(AuthoritativeTier.class);
    }

    @Override
    public void releaseAuthoritativeTier(AuthoritativeTier<?, ?> resource) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void initAuthoritativeTier(AuthoritativeTier<?, ?> resource) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void start(ServiceProvider serviceProvider) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void stop() {
      throw new UnsupportedOperationException();
    }
  }
}
