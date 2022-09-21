package com.linkedin.davinci.ingestion.main;

import com.linkedin.davinci.config.VeniceConfigLoader;
import com.linkedin.davinci.ingestion.IsolatedIngestionBackend;
import com.linkedin.davinci.ingestion.utils.IsolatedIngestionUtils;
import com.linkedin.davinci.stats.MetadataUpdateStats;
import com.linkedin.davinci.storage.StorageMetadataService;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.ingestion.protocol.IngestionStorageMetadata;
import com.linkedin.venice.kafka.protocol.state.PartitionState;
import com.linkedin.venice.kafka.protocol.state.StoreVersionState;
import com.linkedin.venice.meta.IngestionMetadataUpdateType;
import com.linkedin.venice.offsets.OffsetRecord;
import com.linkedin.venice.serialization.avro.InternalAvroSpecificSerializer;
import com.linkedin.venice.service.AbstractVeniceService;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.concurrent.VeniceConcurrentHashMap;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * MainIngestionStorageMetadataService is an in-memory storage metadata service for {@link IsolatedIngestionBackend}.
 * It keeps storage metadata in the memory so RocksDB metadata partitions can be opened by isolated ingestion process only.
 * For metadata update generated by hybrid ingestion, it will sync and persist the update to the RocksDB metadata partition
 * through IPC protocol.
 */
public class MainIngestionStorageMetadataService extends AbstractVeniceService implements StorageMetadataService {
  private static final Logger LOGGER = LogManager.getLogger(MainIngestionStorageMetadataService.class);

  private final MainIngestionRequestClient client;
  private final InternalAvroSpecificSerializer<PartitionState> partitionStateSerializer;
  private final Map<String, Map<Integer, OffsetRecord>> topicPartitionOffsetRecordMap = new VeniceConcurrentHashMap<>();
  private final Map<String, StoreVersionState> topicStoreVersionStateMap = new VeniceConcurrentHashMap<>();
  private final ExecutorService metadataUpdateService = Executors.newSingleThreadExecutor();
  private final Queue<IngestionStorageMetadata> metadataUpdateQueue = new ConcurrentLinkedDeque<>();
  private final MetadataUpdateStats metadataUpdateStats;
  private final MetadataUpdateWorker metadataUpdateWorker;

  public MainIngestionStorageMetadataService(
      int targetPort,
      InternalAvroSpecificSerializer<PartitionState> partitionStateSerializer,
      MetadataUpdateStats metadataUpdateStats,
      VeniceConfigLoader configLoader) {
    this.client = new MainIngestionRequestClient(IsolatedIngestionUtils.getSSLFactory(configLoader), targetPort);
    this.partitionStateSerializer = partitionStateSerializer;
    this.metadataUpdateStats = metadataUpdateStats;
    this.metadataUpdateWorker = new MetadataUpdateWorker();
  }

  @Override
  public boolean startInner() throws Exception {
    metadataUpdateService.execute(metadataUpdateWorker);
    return true;
  }

  @Override
  public void stopInner() throws Exception {
    metadataUpdateWorker.close();
    metadataUpdateService.shutdownNow();
    client.close();
  }

  @Override
  public void put(String topicName, StoreVersionState record) throws VeniceException {
    putStoreVersionState(topicName, record);
    // Sync update with metadata partition opened by ingestion process.
    IngestionStorageMetadata ingestionStorageMetadata = new IngestionStorageMetadata();
    ingestionStorageMetadata.metadataUpdateType = IngestionMetadataUpdateType.PUT_STORE_VERSION_STATE.getValue();
    ingestionStorageMetadata.topicName = topicName;
    ingestionStorageMetadata.payload =
        ByteBuffer.wrap(IsolatedIngestionUtils.serializeStoreVersionState(topicName, record));
    updateRemoteStorageMetadataService(ingestionStorageMetadata);
  }

  @Override
  public void clearStoreVersionState(String topicName) {
    LOGGER.info("Clearing StoreVersionState for " + topicName);
    topicStoreVersionStateMap.remove(topicName);
    // Sync update with metadata partition opened by ingestion process.
    IngestionStorageMetadata ingestionStorageMetadata = new IngestionStorageMetadata();
    ingestionStorageMetadata.metadataUpdateType = IngestionMetadataUpdateType.CLEAR_STORE_VERSION_STATE.getValue();
    ingestionStorageMetadata.topicName = topicName;
    ingestionStorageMetadata.payload = ByteBuffer.wrap(new byte[0]);
    updateRemoteStorageMetadataService(ingestionStorageMetadata);
  }

  @Override
  public Optional<StoreVersionState> getStoreVersionState(String topicName) throws VeniceException {
    if (topicStoreVersionStateMap.containsKey(topicName)) {
      return Optional.of(topicStoreVersionStateMap.get(topicName));
    } else {
      return Optional.empty();
    }
  }

  @Override
  public void put(String topicName, int partitionId, OffsetRecord record) throws VeniceException {
    putOffsetRecord(topicName, partitionId, record);
    // Sync update with metadata partition opened by ingestion process.
    IngestionStorageMetadata ingestionStorageMetadata = new IngestionStorageMetadata();
    ingestionStorageMetadata.metadataUpdateType = IngestionMetadataUpdateType.PUT_OFFSET_RECORD.getValue();
    ingestionStorageMetadata.topicName = topicName;
    ingestionStorageMetadata.partitionId = partitionId;
    ingestionStorageMetadata.payload = ByteBuffer.wrap(record.toBytes());
    updateRemoteStorageMetadataService(ingestionStorageMetadata);
  }

  @Override
  public void clearOffset(String topicName, int partitionId) {
    LOGGER.info("Clearing OffsetRecord for " + topicName + " " + partitionId);
    if (topicPartitionOffsetRecordMap.containsKey(topicName)) {
      Map<Integer, OffsetRecord> partitionOffsetRecordMap = topicPartitionOffsetRecordMap.get(topicName);
      partitionOffsetRecordMap.remove(partitionId);
      topicPartitionOffsetRecordMap.put(topicName, partitionOffsetRecordMap);
    }
    // Sync update with metadata partition opened by ingestion process.
    IngestionStorageMetadata ingestionStorageMetadata = new IngestionStorageMetadata();
    ingestionStorageMetadata.metadataUpdateType = IngestionMetadataUpdateType.CLEAR_OFFSET_RECORD.getValue();
    ingestionStorageMetadata.topicName = topicName;
    ingestionStorageMetadata.partitionId = partitionId;
    ingestionStorageMetadata.payload = ByteBuffer.wrap(new byte[0]);
    updateRemoteStorageMetadataService(ingestionStorageMetadata);
  }

  @Override
  public OffsetRecord getLastOffset(String topicName, int partitionId) throws VeniceException {
    if (topicPartitionOffsetRecordMap.containsKey(topicName)) {
      Map<Integer, OffsetRecord> partitionOffsetRecordMap = topicPartitionOffsetRecordMap.get(topicName);
      return partitionOffsetRecordMap.getOrDefault(partitionId, new OffsetRecord(partitionStateSerializer));
    }
    return new OffsetRecord(partitionStateSerializer);
  }

  /**
   * putOffsetRecord will only put OffsetRecord into in-memory state, without persisting into metadata RocksDB partition.
   */
  public void putOffsetRecord(String topicName, int partitionId, OffsetRecord record) {
    LOGGER
        .info("Updating OffsetRecord for " + topicName + " " + partitionId + " " + record.getLocalVersionTopicOffset());
    Map<Integer, OffsetRecord> partitionOffsetRecordMap =
        topicPartitionOffsetRecordMap.getOrDefault(topicName, new VeniceConcurrentHashMap<>());
    partitionOffsetRecordMap.put(partitionId, record);
    topicPartitionOffsetRecordMap.put(topicName, partitionOffsetRecordMap);
  }

  /**
   * putStoreVersionState will only put StoreVersionState into in-memory state, without persisting into metadata RocksDB partition.
   */
  public void putStoreVersionState(String topicName, StoreVersionState record) {
    LOGGER.info("Updating StoreVersionState for " + topicName);
    topicStoreVersionStateMap.put(topicName, record);
  }

  private synchronized void updateRemoteStorageMetadataService(IngestionStorageMetadata ingestionStorageMetadata) {
    metadataUpdateQueue.add(ingestionStorageMetadata);
    metadataUpdateStats.recordMetadataUpdateQueueLength(metadataUpdateQueue.size());
  }

  /**
   * MetadataUpdateWorker is a Runnable class that pushes local metadata update on the FIFO basis. It will retry updates
   * when updates failed due to connection lost or child process crashes.
   */
  class MetadataUpdateWorker implements Runnable, Closeable {
    private final AtomicBoolean isRunning = new AtomicBoolean(true);

    @Override
    public void close() throws IOException {
      isRunning.set(false);
    }

    @Override
    public void run() {
      while (isRunning.get()) {
        try {
          /**
           * Here it leaves room for future optimization: if the metadata update volumes is large in classical Venice
           * and this sequential update approach is lagging, we can transform it into batch update.
           */
          while (!metadataUpdateQueue.isEmpty()) {
            boolean isSuccess = client.updateMetadata(metadataUpdateQueue.peek());
            if (isSuccess) {
              metadataUpdateQueue.remove();
              metadataUpdateStats.recordMetadataUpdateQueueLength(metadataUpdateQueue.size());
            } else {
              if (!isRunning.get()) {
                break;
              }
              Thread.sleep(5 * Time.MS_PER_SECOND);
            }
          }
          Thread.sleep(Time.MS_PER_SECOND);
          if (metadataUpdateQueue.size() > 0) {
            LOGGER.info("Number of remaining metadata update requests in queue: " + metadataUpdateQueue.size());
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        } catch (Throwable e) {
          LOGGER.error("Unexpected throwable while running " + getClass().getSimpleName(), e);
          metadataUpdateStats.recordMetadataQueueUpdateError(1.0);
        }
      }
    }
  }
}