/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.record_buffer;

import io.airbyte.commons.concurrency.VoidCallable;
import io.airbyte.integrations.base.AirbyteStreamNameNamespacePair;
import io.airbyte.integrations.base.sentry.AirbyteSentry;
import io.airbyte.integrations.destination.buffered_stream_consumer.CheckAndRemoveRecordWriter;
import io.airbyte.integrations.destination.buffered_stream_consumer.RecordSizeEstimator;
import io.airbyte.integrations.destination.buffered_stream_consumer.RecordWriter;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.protocol.models.AirbyteRecordMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the default implementation of a {@link BufferStorage} to be backward compatible. Data is
 * being buffered in a {@link List<AirbyteRecordMessage>} as they are being consumed.
 *
 * This should be deprecated as we slowly move towards using {@link SerializedBufferingStrategy}
 * instead.
 */
public class InMemoryRecordBufferingStrategy implements BufferingStrategy {

  private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryRecordBufferingStrategy.class);

  private Map<AirbyteStreamNameNamespacePair, List<AirbyteRecordMessage>> streamBuffer = new HashMap<>();
  private final RecordWriter<AirbyteRecordMessage> recordWriter;
  private final CheckAndRemoveRecordWriter checkAndRemoveRecordWriter;
  private String fileName;

  private final RecordSizeEstimator recordSizeEstimator;
  private final long maxQueueSizeInBytes;
  private long bufferSizeInBytes;
  private VoidCallable onFlushAllEventHook;

  public InMemoryRecordBufferingStrategy(final RecordWriter<AirbyteRecordMessage> recordWriter,
                                         final long maxQueueSizeInBytes) {
    this(recordWriter, null, maxQueueSizeInBytes);
  }

  public InMemoryRecordBufferingStrategy(final RecordWriter<AirbyteRecordMessage> recordWriter,
                                         final CheckAndRemoveRecordWriter checkAndRemoveRecordWriter,
                                         final long maxQueueSizeInBytes) {
    this.recordWriter = recordWriter;
    this.checkAndRemoveRecordWriter = checkAndRemoveRecordWriter;

    this.maxQueueSizeInBytes = maxQueueSizeInBytes;
    this.bufferSizeInBytes = 0;
    this.recordSizeEstimator = new RecordSizeEstimator();
    this.onFlushAllEventHook = null;
  }

  @Override
  public void addRecord(final AirbyteStreamNameNamespacePair stream, final AirbyteMessage message) throws Exception {
    final long messageSizeInBytes = recordSizeEstimator.getEstimatedByteSize(message.getRecord());
    if (bufferSizeInBytes + messageSizeInBytes > maxQueueSizeInBytes) {
      flushAll();
      bufferSizeInBytes = 0;
    }

    final List<AirbyteRecordMessage> bufferedRecords = streamBuffer.computeIfAbsent(stream, k -> new ArrayList<>());
    bufferedRecords.add(message.getRecord());
    bufferSizeInBytes += messageSizeInBytes;
  }

  @Override
  public void flushWriter(final AirbyteStreamNameNamespacePair stream, final SerializableBuffer writer) throws Exception {
    LOGGER.info("Flushing single stream {}: {} records", stream, streamBuffer.get(stream).size());
    recordWriter.accept(stream, streamBuffer.get(stream));
  }

  @Override
  public void flushAll() throws Exception {
    AirbyteSentry.executeWithTracing("FlushBuffer", () -> {
      for (final Map.Entry<AirbyteStreamNameNamespacePair, List<AirbyteRecordMessage>> entry : streamBuffer.entrySet()) {
        LOGGER.info("Flushing {}: {} records", entry.getKey().getName(), entry.getValue().size());
        recordWriter.accept(entry.getKey(), entry.getValue());
        if (checkAndRemoveRecordWriter != null) {
          fileName = checkAndRemoveRecordWriter.apply(entry.getKey(), fileName);
        }
      }
    }, Map.of("bufferSizeInBytes", bufferSizeInBytes));
    close();
    clear();

    if (onFlushAllEventHook != null) {
      onFlushAllEventHook.call();
    }
  }

  @Override
  public void clear() {
    streamBuffer = new HashMap<>();
  }

  @Override
  public void registerFlushAllEventHook(final VoidCallable onFlushAllEventHook) {
    this.onFlushAllEventHook = onFlushAllEventHook;
  }

  @Override
  public void close() throws Exception {}

}
