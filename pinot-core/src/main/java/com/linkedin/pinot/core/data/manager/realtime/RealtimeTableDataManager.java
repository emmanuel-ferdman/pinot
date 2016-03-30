/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.core.data.manager.realtime;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.helix.ZNRecord;
import org.apache.helix.store.zk.ZkHelixPropertyStore;
import org.slf4j.LoggerFactory;
import com.linkedin.pinot.common.config.AbstractTableConfig;
import com.linkedin.pinot.common.data.Schema;
import com.linkedin.pinot.common.metadata.ZKMetadataProvider;
import com.linkedin.pinot.common.metadata.instance.InstanceZKMetadata;
import com.linkedin.pinot.common.metadata.segment.RealtimeSegmentZKMetadata;
import com.linkedin.pinot.common.metadata.segment.SegmentZKMetadata;
import com.linkedin.pinot.common.segment.SegmentMetadata;
import com.linkedin.pinot.common.utils.CommonConstants.Segment.Realtime.Status;
import com.linkedin.pinot.common.utils.NamedThreadFactory;
import com.linkedin.pinot.common.utils.helix.PinotHelixPropertyStoreZnRecordProvider;
import com.linkedin.pinot.core.data.manager.offline.AbstractTableDataManager;
import com.linkedin.pinot.core.data.manager.offline.SegmentDataManager;
import com.linkedin.pinot.core.indexsegment.IndexSegment;
import com.linkedin.pinot.core.indexsegment.columnar.ColumnarSegmentLoader;


// TODO Use the refcnt object inside SegmentDataManager
public class RealtimeTableDataManager extends AbstractTableDataManager {

//  private final Object _globalLock = new Object();
//  private boolean _isStarted = false;

  private final ExecutorService _segmentAsyncExecutorService = Executors
      .newSingleThreadExecutor(new NamedThreadFactory("SegmentAsyncExecutorService"));
  private ZkHelixPropertyStore<ZNRecord> _helixPropertyStore;

  public RealtimeTableDataManager() {
    super();
  }

  @Override
  protected void doShutdown() {
    _segmentAsyncExecutorService.shutdown();
    for (SegmentDataManager segmentDataManager :_segmentsMap.values() ) {
      segmentDataManager.destroy();
    }
  }

  protected void doInit() {
      LOGGER = LoggerFactory.getLogger(_tableName + "-RealtimeTableDataManager");
  }

  public void notifySegmentCommitted(RealtimeSegmentZKMetadata metadata, IndexSegment segment) {
    ZKMetadataProvider.setRealtimeSegmentZKMetadata(_helixPropertyStore, metadata);
    markSegmentAsLoaded(metadata.getSegmentName());
    addSegment(segment);
  }

  /*
   * This call comes in one of two ways:
   * - We are being directed by helix to own up all the segments that we committed and are still in retention. In this case
   *   we treat it exactly like how OfflineTableDataManager would -- wrap it into an OfflineSegmentDataManager, and put it
   *   in the map.
   * - We are being asked to own up a new realtime segment. In this case, we wrap the segment with a RealTimeSegmentDataManager
   *   (that kicks off Kafka consumption). When the segment is committed we get notified via the notifySegmentCommitted call, at
   *   which time we replace the segment with the OfflineSegmentDataManager
   */
  @Override
  public void addSegment(ZkHelixPropertyStore<ZNRecord> propertyStore, AbstractTableConfig tableConfig,
      InstanceZKMetadata instanceZKMetadata, SegmentZKMetadata inputSegmentZKMetadata) throws Exception {
    // TODO FIXME
    // Hack. We get the _helixPropertyStore here and save it, knowing that we will get this addSegment call
    // before the notifyCommitted call (that uses _helixPropertyStore)
    this._helixPropertyStore = propertyStore;

    final String segmentId = inputSegmentZKMetadata.getSegmentName();
    final String tableName = inputSegmentZKMetadata.getTableName();
    if (!(inputSegmentZKMetadata instanceof  RealtimeSegmentZKMetadata)) {
      LOGGER.warn("Got called with an unexpected instance object:{},table {}, segment {}",
          inputSegmentZKMetadata.getClass().getSimpleName(), tableName, segmentId);
      return;
    }
    RealtimeSegmentZKMetadata segmentZKMetadata = (RealtimeSegmentZKMetadata) inputSegmentZKMetadata;

    if (new File(_indexDir, segmentId).exists() && (segmentZKMetadata).getStatus() == Status.DONE) {
      // segment already exists on file, and we have committed the realtime segment in ZK. Treat it like an offline segment
      if (_segmentsMap.containsKey(segmentId)) {
        LOGGER.warn("Got reload for segment already on disk {} table {}, have {}", segmentId, tableName,
            _segmentsMap.get(segmentId).getClass().getSimpleName());
        return;
      }

      IndexSegment segment = ColumnarSegmentLoader.load(new File(_indexDir, segmentId), _readMode, _indexLoadingConfigMetadata);
      addSegment(segment);
      markSegmentAsLoaded(segmentId);
    } else {
      // Either we don't have the segment on disk or we have not committed in ZK. We should be starting the consumer
      // for realtime segment here. If we wrote it on disk but could not get to commit to zk yet, we should replace the
      // on-disk segment next time
      if (_segmentsMap.containsKey(segmentId)) {
        LOGGER.warn("Got reload for segment not on disk {} table {}, have {}", segmentId, tableName,
            _segmentsMap.get(segmentId).getClass().getSimpleName());
        return;
      }
      PinotHelixPropertyStoreZnRecordProvider propertyStoreHelper = PinotHelixPropertyStoreZnRecordProvider.forSchema(propertyStore);
      ZNRecord record = propertyStoreHelper.get(tableConfig.getValidationConfig().getSchemaName());
      LOGGER.info("found schema {} ", tableConfig.getValidationConfig().getSchemaName());
      SegmentDataManager manager =
          new RealtimeSegmentDataManager(segmentZKMetadata, tableConfig,
              instanceZKMetadata, this, _indexDir.getAbsolutePath(), _readMode, Schema.fromZNRecord(record),
              _serverMetrics);
      LOGGER.info("Initialize RealtimeSegmentDataManager - " + segmentId);
      try {
        _rwLock.writeLock().lock();
        _segmentsMap.put(segmentId, manager);
      } finally {
        _rwLock.writeLock().unlock();
      }
      _loadingSegments.add(segmentId);
    }
  }

  @Override
  public void addSegment(SegmentMetadata segmentMetaToAdd) throws Exception {
    throw new UnsupportedOperationException("Not supported addSegment(SegmentMetadata) in RealtimeTableDataManager"
      + segmentMetaToAdd.getName() + "," + segmentMetaToAdd.getTableName());
  }

  private void markSegmentAsLoaded(String segmentId) {
    _loadingSegments.remove(segmentId);
    if (!_activeSegments.contains(segmentId)) {
      _activeSegments.add(segmentId);
    }
  }
}
