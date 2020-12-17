package com.linkedin.venice.helix;

import com.linkedin.venice.common.VeniceSystemStoreType;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.ZkServerWrapper;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.SystemStoreAttributes;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.utils.TestUtils;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.helix.zookeeper.impl.client.ZkClient;
import org.apache.zookeeper.CreateMode;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.*;


public class TestHelixReadWriteStoreRepositoryAdapter {
  private String zkAddress;
  private ZkClient zkClient;
  private String cluster = "test-metadata-cluster";
  private String clusterPath = "/test-metadata-cluster";
  private String storesPath = "/stores";
  private ZkServerWrapper zkServerWrapper;
  private HelixAdapterSerializer adapter = new HelixAdapterSerializer();

  private HelixReadWriteStoreRepositoryAdapter writeRepoAdapter;
  private HelixReadOnlyStoreRepository readOnlyRepo;

  private final VeniceSystemStoreType systemStoreType = VeniceSystemStoreType.META_STORE;
  private String regularStoreName;

  @BeforeClass
  public void zkSetup() {
    zkServerWrapper = ServiceFactory.getZkServer();
    zkAddress = zkServerWrapper.getAddress();
    zkClient = ZkClientFactory.newZkClient(zkAddress);
    zkClient.setZkSerializer(adapter);
    zkClient.create(clusterPath, null, CreateMode.PERSISTENT);
    zkClient.create(clusterPath + storesPath, null, CreateMode.PERSISTENT);

    HelixReadOnlyZKSharedSystemStoreRepository zkSharedSystemStoreRepository = new HelixReadOnlyZKSharedSystemStoreRepository(zkClient, adapter, cluster);
    zkSharedSystemStoreRepository.refresh();
    HelixReadWriteStoreRepository writeRepo = new HelixReadWriteStoreRepository(zkClient, adapter, cluster, 1, 1000);
    writeRepo.refresh();

    writeRepoAdapter = new HelixReadWriteStoreRepositoryAdapter(zkSharedSystemStoreRepository, writeRepo);
    // Create zk shared store first
    Store zkSharedStore = TestUtils.createTestStore(systemStoreType.getZkSharedStoreName(), "test_system_store_owner", 1);
    zkSharedStore.setLeaderFollowerModelEnabled(true);
    zkSharedStore.setBatchGetLimit(1);
    zkSharedStore.setReadComputationEnabled(false);
    writeRepo.addStore(zkSharedStore);
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> assertTrue(writeRepo.hasStore(systemStoreType.getZkSharedStoreName())));
    // Create one regular store
    regularStoreName = TestUtils.getUniqueString("test_store");
    Store s1 = TestUtils.createTestStore(regularStoreName, "owner", System.currentTimeMillis());
    s1.increaseVersion();
    s1.setReadQuotaInCU(100);
    s1.setBatchGetLimit(100);
    s1.setReadComputationEnabled(true);
    writeRepo.addStore(s1);

    readOnlyRepo = new HelixReadOnlyStoreRepository(zkClient, adapter, cluster, 1, 1000);
    readOnlyRepo.refresh();
  }

  @AfterClass
  public void zkCleanup() {
    readOnlyRepo.clear();
    writeRepoAdapter.clear();
    zkClient.deleteRecursively(clusterPath);
    zkClient.close();
    zkServerWrapper.close();
  }

  @Test
  public void testAddStore() {
    // Add a regular store
    String anotherRegularStoreName = TestUtils.getUniqueString("test_store");
    Store testStore = TestUtils.createTestStore(anotherRegularStoreName, "test_owner", 0);
    writeRepoAdapter.addStore(testStore);
    // Verify the store via read only repo
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
      assertTrue(readOnlyRepo.hasStore(anotherRegularStoreName));
      assertTrue(writeRepoAdapter.hasStore(systemStoreType.getSystemStoreName(anotherRegularStoreName)));
    });
    // Adding a system store directly will fail
    assertThrows(() -> writeRepoAdapter.addStore(TestUtils.createTestStore(systemStoreType.getSystemStoreName(anotherRegularStoreName), "test_owner", 0)));
    // Other existing types of zk shared system store can still be added
    String newRepositoryUnSupportedZKSharedSystemStoreName = VeniceSystemStoreType.METADATA_STORE.getSystemStoreName(cluster);
    writeRepoAdapter.addStore(TestUtils.createTestStore(newRepositoryUnSupportedZKSharedSystemStoreName, "test_unsupported_system_store_owner", 0));
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
      assertTrue(readOnlyRepo.hasStore(newRepositoryUnSupportedZKSharedSystemStoreName));
      assertTrue(writeRepoAdapter.hasStore(newRepositoryUnSupportedZKSharedSystemStoreName));
    });
  }

  @Test
  public void testDeleteStore() {
    // Add a regular store
    String anotherRegularStoreName = TestUtils.getUniqueString("test_store");
    Store testStore = TestUtils.createTestStore(anotherRegularStoreName, "test_owner", 0);
    writeRepoAdapter.addStore(testStore);
    // Verify the store via read only repo
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
      assertTrue(readOnlyRepo.hasStore(anotherRegularStoreName));
      assertTrue(writeRepoAdapter.hasStore(systemStoreType.getSystemStoreName(anotherRegularStoreName)));
    });
    writeRepoAdapter.deleteStore(anotherRegularStoreName);
    // Deleting a system store directly will fail
    assertThrows(() -> writeRepoAdapter.deleteStore(systemStoreType.getSystemStoreName(anotherRegularStoreName)));

    // Verify the store via read only repo
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
      assertFalse(readOnlyRepo.hasStore(anotherRegularStoreName));
      assertFalse(writeRepoAdapter.hasStore(systemStoreType.getSystemStoreName(anotherRegularStoreName)));
    });

    // Other existing types of zk shared system store can still be added/deleted
    String newRepositoryUnSupportedZKSharedSystemStoreName = VeniceSystemStoreType.DAVINCI_PUSH_STATUS_STORE.getSystemStoreName(cluster);
    writeRepoAdapter.addStore(TestUtils.createTestStore(newRepositoryUnSupportedZKSharedSystemStoreName, "test_unsupported_system_store_owner", 0));
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
      assertTrue(readOnlyRepo.hasStore(newRepositoryUnSupportedZKSharedSystemStoreName));
      assertTrue(writeRepoAdapter.hasStore(newRepositoryUnSupportedZKSharedSystemStoreName));
    });
    writeRepoAdapter.deleteStore(newRepositoryUnSupportedZKSharedSystemStoreName);
    // Verify the store via read only repo
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
      assertFalse(readOnlyRepo.hasStore(newRepositoryUnSupportedZKSharedSystemStoreName));
      assertFalse(writeRepoAdapter.hasStore(newRepositoryUnSupportedZKSharedSystemStoreName));
    });
  }

  @Test
  public void testUpdateStore() {
    // Add a regular store
    String anotherRegularStoreName = TestUtils.getUniqueString("test_store");
    Store testStore = TestUtils.createTestStore(anotherRegularStoreName, "test_owner", 0);
    writeRepoAdapter.addStore(testStore);
    // Verify the store via read only repo
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
      assertTrue(readOnlyRepo.hasStore(anotherRegularStoreName));
      assertTrue(writeRepoAdapter.hasStore(systemStoreType.getSystemStoreName(anotherRegularStoreName)));
      assertEquals(readOnlyRepo.getStore(anotherRegularStoreName).getBatchGetLimit(), -1);
    });

    // Try to update a regular store
    testStore.setBatchGetLimit(1000);
    writeRepoAdapter.updateStore(testStore);
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
      assertEquals(readOnlyRepo.getStore(anotherRegularStoreName).getBatchGetLimit(), 1000);
    });

    // System stores should be empty before any system store update
    assertTrue(readOnlyRepo.getStore(anotherRegularStoreName).getSystemStores().isEmpty());

    // Test to update a system store
    Store systemStore = writeRepoAdapter.getStore(systemStoreType.getSystemStoreName(anotherRegularStoreName));
    systemStore.addVersion(new Version(systemStore.getName(), 1, "test_push_id_1"));
    writeRepoAdapter.updateStore(systemStore);
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
      Map<String, SystemStoreAttributes> systemStores = readOnlyRepo.getStore(anotherRegularStoreName).getSystemStores();
      assertEquals(systemStores.size(), 1);
      assertTrue(systemStores.containsKey(systemStoreType.getPrefix()));
      // a new system store version should present
      assertEquals(systemStores.get(systemStoreType.getPrefix()).getVersions().size(), 1);
    });
  }
}