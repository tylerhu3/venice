package com.linkedin.venice.storage;

import com.linkedin.venice.config.VeniceStoreConfig;
import com.linkedin.venice.server.PartitionAssignmentRepository;
import com.linkedin.venice.server.VeniceConfigService;
import com.linkedin.venice.store.Store;
import com.linkedin.venice.store.memory.InMemoryStorageEngine;
import org.testng.annotations.Test;

import java.io.File;
import java.util.Map;


public class InMemoryStoreTest extends AbstractStoreTest{

  PartitionAssignmentRepository partitionAssignmentRepository;
  VeniceConfigService veniceConfigService;

  public InMemoryStoreTest()
      throws Exception {
    createStoreForTest();
  }

  @Override
  public void createStoreForTest()
      throws Exception {
    File configFile = new File("src/test/resources/config"); //TODO this does not run from IDE because IDE expects
    // relative path starting from venice-server
    veniceConfigService = new VeniceConfigService(configFile.getAbsolutePath());
    Map<String, VeniceStoreConfig> storeConfigs = veniceConfigService.getAllStoreConfigs();

    if(storeConfigs.size() < 1){
      throw new Exception("No stores defined for executing tests");
    }

    String storeName = "testng-in-memory";
    VeniceStoreConfig storeConfig = storeConfigs.get(storeName);

    //populate partitionNodeAssignment
    partitionAssignmentRepository = new PartitionAssignmentRepository();
    int nodeId = 0;
    // only adding 1 partition, config indicates 5 partitions
    partitionAssignmentRepository.addPartition(storeName, 0);

    Store inMemoryStorageEngine = new InMemoryStorageEngine(storeConfig, partitionAssignmentRepository);
    this.testStore = inMemoryStorageEngine;
  }

  @Test
  public void testGetAndPut(){
    super.testGetAndPut();
  }

  @Test
  public void testDelete(){
    super.testDelete();
  }

  @Test
  public void testUpdate() {
    super.testUpdate();
  }

  @Test
  public void testGetInvalidKeys()
  {
    super.testGetInvalidKeys();
  }

  @Test
  public void testPutNullKey(){
    super.testPutNullKey();
  }


}
