/**
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
package io.streamnative.pulsar.handlers.kop.coordinator.transaction;

import com.google.common.collect.Maps;
import io.netty.buffer.Unpooled;
import io.streamnative.pulsar.handlers.kop.utils.CoreUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.internals.Topic;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.ProduceResponse;
import org.apache.kafka.common.requests.TransactionResult;
import org.apache.kafka.common.utils.SystemTime;
import org.apache.kafka.common.utils.Utils;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.common.naming.TopicName;


/**
 * Transaction state manager.
 */
@Slf4j
public class TransactionStateManager {

    private final TransactionConfig transactionConfig;
    private final PulsarClient pulsarClient;
    private ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();

    // Number of partitions for the transaction log topic.
    private final int transactionTopicPartitionCount;

    // Partitions of transaction topic that are being loaded, state lock should be called BEFORE accessing this set.
    private final Set<Integer> loadingPartitions = new HashSet<>();

    private Map<Integer, CompletableFuture<Producer<byte[]>>> txnLogProducerMap = new HashMap<>();
    private Map<Integer, CompletableFuture<Reader<byte[]>>> txnLogReaderMap = new HashMap<>();
    // Transaction metadata cache indexed by assigned transaction topic partition ids
    private Map<Integer, Map<String, TransactionMetadata>> transactionMetadataCache = new HashMap<>();

    private ExecutorService executor;

    public TransactionStateManager(TransactionConfig transactionConfig,
                                   PulsarClient pulsarClient,
                                   ScheduledExecutorService executor) {
        this.transactionConfig = transactionConfig;
        this.pulsarClient = pulsarClient;
        this.executor = executor;
        this.transactionTopicPartitionCount = transactionConfig.getTransactionLogNumPartitions();
    }

    /**
     * TxnMetadataCacheEntry.
     */
    @AllArgsConstructor
    private static class TxnMetadataCacheEntry {
        private Integer coordinatorEpoch;
        private Map<String, TransactionMetadata> metadataPerTransactionalId;

        @Override
        public String toString() {
            return "TxnMetadataCacheEntry{"
                    + "coordinatorEpoch=" + coordinatorEpoch
                    + ", numTransactionalEntries=" + metadataPerTransactionalId.size()
                    + '}';
        }
    }

    /**
     * CoordinatorEpoch and TxnMetadata.
     */
    @Data
    @AllArgsConstructor
    public static class CoordinatorEpochAndTxnMetadata {
        private Integer coordinatorEpoch;
        private TransactionMetadata transactionMetadata;
    }

    /**
     * TransactionalId, coordinatorEpoch and TransitMetadata.
     */
    @AllArgsConstructor
    private static class TransactionalIdAndTransitMetadata {
        private final String transactionalId;
        private TransactionResult result;
        private TransactionMetadata txnMetadata;
        private TransactionMetadata.TxnTransitMetadata transitMetadata;
    }

    public void appendTransactionToLog(String transactionalId,
                                       int coordinatorEpoch,
                                       TransactionMetadata.TxnTransitMetadata newMetadata,
                                       ResponseCallback responseCallback,
                                       RetryOnError retryOnError) {

        // generate the message for this transaction metadata
        TopicPartition topicPartition = new TopicPartition(
                Topic.TRANSACTION_STATE_TOPIC_NAME, partitionFor(transactionalId));

        CoreUtils.inReadLock(stateLock, () -> {
            // we need to hold the read lock on the transaction metadata cache until appending to local log returns;
            // this is to avoid the case where an emigration followed by an immigration could have completed after the
            // check returns and before appendRecords() is called, since otherwise entries with a high coordinator epoch
            // could have been appended to the log in between these two events, and therefore appendRecords() would
            // append entries with an old coordinator epoch that can still be successfully replicated on followers
            // and make the log in a bad state.
            ErrorsAndData<Optional<CoordinatorEpochAndTxnMetadata>> errorsAndData =
                    getTransactionState(transactionalId);

            if (errorsAndData.hasErrors()) {
                responseCallback.fail(errorsAndData.getErrors());
                return null;
            }

            if (!errorsAndData.getData().isPresent()) {
                responseCallback.fail(Errors.NOT_COORDINATOR);
                return null;
            }

            CoordinatorEpochAndTxnMetadata epochAndMetadata = errorsAndData.getData().get();
            TransactionMetadata metadata = epochAndMetadata.getTransactionMetadata();
            metadata.inLock(() -> {
                if (epochAndMetadata.getCoordinatorEpoch() != coordinatorEpoch) {
                    // the coordinator epoch has changed, reply to client immediately with NOT_COORDINATOR
                    responseCallback.fail(Errors.NOT_COORDINATOR);
                    return null;
                }
                storeTxnLog(transactionalId, newMetadata).thenAccept(messageId -> {
                    Map<TopicPartition, ProduceResponse.PartitionResponse> partitionResponseMap = new HashMap<>();
                    partitionResponseMap.put(topicPartition, new ProduceResponse.PartitionResponse(Errors.NONE));
                    updateCacheCallback(transactionalId, newMetadata, topicPartition, coordinatorEpoch,
                            partitionResponseMap, responseCallback, retryOnError);
                    log.info("Appending new metadata {} for transaction id {} to the local transaction log with "
                            + "messageId {}", newMetadata, transactionalId, messageId);
                });
                return null;
            });
            return null;
        });
    }

    // set the callback function to update transaction status in cache after log append completed
    private void updateCacheCallback(String transactionalId,
                                     TransactionMetadata.TxnTransitMetadata newMetadata,
                                     TopicPartition topicPartition,
                                     int coordinatorEpoch,
                                     Map<TopicPartition, ProduceResponse.PartitionResponse> responseStatus,
                                     ResponseCallback responseCallback,
                                     RetryOnError retryOnError) {
        // the append response should only contain the topics partition
        if (responseStatus.size() != 1 || !responseStatus.containsKey(topicPartition)) {
            throw new IllegalStateException(String.format("Append status %s should only have one partition %s",
                    responseStatus, topicPartition));
        }

        ProduceResponse.PartitionResponse status = responseStatus.get(topicPartition);
        ErrorsAndData<Void> result = statusCheck(transactionalId, newMetadata, status);

        if (!result.hasErrors()) {
            validStatus(transactionalId, newMetadata, result, coordinatorEpoch);
        } else {
            invalidStatus(transactionalId, newMetadata, result, coordinatorEpoch, retryOnError);
        }

        if (result.hasErrors()) {
            responseCallback.fail(result.getErrors());
        } else {
            responseCallback.complete();
        }
    }

    private ErrorsAndData<Void> statusCheck(String transactionalId,
                                            TransactionMetadata.TxnTransitMetadata newMetadata,
                                            ProduceResponse.PartitionResponse status) {
        ErrorsAndData<Void> result = new ErrorsAndData<>();
        if (status.error == Errors.NONE) {
            result.setErrors(Errors.NONE);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Appending {}'s new metadata {} failed due to {}",
                        transactionalId, newMetadata, status.error.exceptionName());
            }

            // transform the log append error code to the corresponding coordinator error code
            switch (status.error) {
                case UNKNOWN_TOPIC_OR_PARTITION:
                case NOT_ENOUGH_REPLICAS:
                case NOT_ENOUGH_REPLICAS_AFTER_APPEND:
                case REQUEST_TIMED_OUT:
                    // note that for timed out request we return NOT_AVAILABLE error code to let client retry
                    result.setErrors(Errors.COORDINATOR_NOT_AVAILABLE);
                    break;
                case KAFKA_STORAGE_ERROR:
//                case Errors.NOT_LEADER_OR_FOLLOWER:
                    result.setErrors(Errors.NOT_COORDINATOR);
                    break;
                case MESSAGE_TOO_LARGE:
                case RECORD_LIST_TOO_LARGE:
                    result.setErrors(Errors.UNKNOWN_SERVER_ERROR);
                    break;
                default:
                    result.setErrors(Errors.UNKNOWN_SERVER_ERROR);
                    break;
            }
        }
        return result;
    }

    private void validStatus(String transactionalId,
                             TransactionMetadata.TxnTransitMetadata newMetadata,
                             ErrorsAndData<Void> result,
                             int coordinatorEpoch) {
        // now try to update the cache: we need to update the status in-place instead of
        // overwriting the whole object to ensure synchronization
        ErrorsAndData<Optional<CoordinatorEpochAndTxnMetadata>> errorsAndData =
                getTransactionState(transactionalId);

        if (errorsAndData.hasErrors()) {
            log.info("Accessing the cached transaction metadata for {} returns {} error; "
                            + "aborting transition to the new metadata and setting the error in the callback",
                    transactionalId, errorsAndData.getErrors());
            result.setErrors(errorsAndData.getErrors());
        } else if (!errorsAndData.getData().isPresent()) {
            // this transactional id no longer exists, maybe the corresponding partition has already been migrated
            // out. return NOT_COORDINATOR to let the client re-discover the transaction coordinator
            log.info("The cached coordinator metadata does not exist in the cache anymore for {} after appended "
                            + "its new metadata {} to the transaction log (txn topic partition {}) while it was {}"
                            + " before appending; " + "aborting transition to the new metadata and returning {} "
                            + "in the callback",
                    transactionalId, newMetadata, partitionFor(transactionalId), coordinatorEpoch,
                    Errors.NOT_COORDINATOR);
            result.setErrors(Errors.NOT_COORDINATOR);
        } else {
            TransactionMetadata metadata = errorsAndData.getData().get().transactionMetadata;

            metadata.inLock(() -> {
                if (errorsAndData.getData().get().coordinatorEpoch != coordinatorEpoch) {
                    // the cache may have been changed due to txn topic partition emigration and immigration,
                    // in this case directly return NOT_COORDINATOR to client and let it to re-discover the
                    // transaction coordinator
                    log.info("The cached coordinator epoch for {} has changed to {} after appended its new "
                                    + "metadata {} to the transaction log (txn topic partition {}) while it was "
                                    + "{} before appending; aborting transition to the new metadata and returning "
                                    + "{} in the callback",
                            transactionalId, coordinatorEpoch, newMetadata, partitionFor(transactionalId),
                            coordinatorEpoch, Errors.NOT_CONTROLLER);
                    result.setErrors(Errors.NOT_COORDINATOR);
                } else {
                    try {
                        if (log.isDebugEnabled()) {
                            log.debug("Updating {}'s transaction state to {} with coordinator epoch {} for {} "
                                    + "successed", transactionalId, newMetadata, coordinatorEpoch, transactionalId);

                        }
                        metadata.completeTransitionTo(newMetadata);
                    } catch (Exception e) {
                        log.error("Failed to complete transition.", e);
                        result.setErrors(Errors.forException(e));
                    }
                }
                return null;
            });
        }
    }

    private void invalidStatus(String transactionalId,
                               TransactionMetadata.TxnTransitMetadata newMetadata,
                               ErrorsAndData<Void> result,
                               int coordinatorEpoch,
                               RetryOnError retryOnError) {
        ErrorsAndData<Optional<CoordinatorEpochAndTxnMetadata>> errorsAndData =
                getTransactionState(transactionalId);

        // Reset the pending state when returning an error, since there is no active transaction for the
        // transactional id at this point.
        if (errorsAndData.hasErrors()) {
            // Do nothing here, since we want to return the original append error to the user.
            log.info("TransactionalId {} append transaction log for {} transition failed due to {}, aborting state "
                    + "transition and returning the error in the callback since retrieving metadata "
                    + "returned {}", transactionalId, newMetadata, result.getErrors(), errorsAndData.getErrors());

        } else if (!errorsAndData.getData().isPresent()) {
            // Do nothing here, since we want to return the original append error to the user.
            log.info("TransactionalId {} append transaction log for {} transition failed due to {}, aborting state "
                    + "transition and returning the error in the callback since metadata is not available in the "
                    + "cache anymore", transactionalId, newMetadata, result.getErrors());
        } else {
            TransactionMetadata metadata = errorsAndData.getData().get().transactionMetadata;
            metadata.inLock(() -> {
                if (errorsAndData.getData().get().coordinatorEpoch == coordinatorEpoch) {
                    if (retryOnError.retry(result.getErrors())) {
                        log.info("TransactionalId {} append transaction log for {} transition failed due to {}, "
                                        + "not resetting pending state {} but just returning the error in the callback "
                                        + "to let the caller retry",
                                metadata.getTransactionalId(), newMetadata, result.getErrors(),
                                metadata.getPendingState());
                    } else {
                        log.info("TransactionalId {} append transaction log for {} transition failed due to {}, "
                                    + "resetting pending state from {}, aborting state transition and returning {} in "
                                    + "the callback",
                                metadata.getTransactionalId(), newMetadata, result.getErrors(),
                                metadata.getPendingState(), result.getErrors());
                        metadata.setPendingState(Optional.empty());
                    }
                } else {
                    log.info("TransactionalId {} append transaction log for {} transition failed due to {}, "
                                    + "aborting state transition and returning the error in the callback since the "
                                    + "coordinator epoch has changed from {} to {}", metadata.getTransactionalId(),
                            newMetadata, result.getErrors(), errorsAndData.getData().get().coordinatorEpoch,
                            coordinatorEpoch);
                }
                return null;
            });
        }
    }

    /**
     * Response callback interface.
     */
    public interface ResponseCallback {
        void complete();
        void fail(Errors errors);
    }

    /**
     * Retry on error.
     */
    public interface RetryOnError {
        boolean retry(Errors errors);
    }

    public ErrorsAndData<Optional<CoordinatorEpochAndTxnMetadata>> getTransactionState(String transactionalId) {
        return getAndMaybeAddTransactionState(transactionalId, Optional.empty());
    }

    public ErrorsAndData<Optional<CoordinatorEpochAndTxnMetadata>> putTransactionStateIfNotExists(
            TransactionMetadata metadata) {
        ErrorsAndData<Optional<CoordinatorEpochAndTxnMetadata>> errorsAndData =
                getAndMaybeAddTransactionState(metadata.getTransactionalId(), Optional.of(metadata));
        if (!errorsAndData.getData().isPresent()) {
            throw new IllegalStateException("Unexpected empty transaction metadata returned while putting " + metadata);
        }
        return errorsAndData;
    }

    /**
     * Validate the given transaction timeout value.
     */
    public boolean validateTransactionTimeoutMs(int txnTimeoutMs) {
        return txnTimeoutMs <= transactionConfig.getTransactionMaxTimeoutMs() && txnTimeoutMs > 0;
    }

    /**
     * Get the transaction metadata associated with the given transactional id, or an error if
     * the coordinator does not own the transaction partition or is still loading it; if not found
     * either return None or create a new metadata and added to the cache.
     * This function is covered by the state read lock.
     */
    private ErrorsAndData<Optional<CoordinatorEpochAndTxnMetadata>> getAndMaybeAddTransactionState(
            String transactionalId,
            Optional<TransactionMetadata> createdTxnMetadataOpt) {
        return CoreUtils.inReadLock(stateLock, () -> {
            int partitionId = partitionFor(transactionalId);
            if (loadingPartitions.stream().anyMatch(partition -> partition == partitionId)) {
                return new ErrorsAndData<>(Errors.CONCURRENT_TRANSACTIONS);
            } else {
                Map<String, TransactionMetadata> metadataMap = transactionMetadataCache.get(partitionId);
                if (metadataMap == null) {
                    return new ErrorsAndData<>(Errors.NOT_COORDINATOR);
                }
                Optional<TransactionMetadata> txnMetadata;
                TransactionMetadata txnMetadataCache = metadataMap.get(transactionalId);
                if (txnMetadataCache == null) {
                    if (createdTxnMetadataOpt.isPresent()) {
                        metadataMap.put(transactionalId, createdTxnMetadataOpt.get());
                        txnMetadata = createdTxnMetadataOpt;
                    } else {
                        txnMetadata = Optional.empty();
                    }
                } else {
                    txnMetadata = Optional.of(txnMetadataCache);
                }

                return txnMetadata
                        .map(metadata -> new ErrorsAndData<>(
                                Optional.of(new CoordinatorEpochAndTxnMetadata(-1, metadata))))
                        .orElseGet(() -> new ErrorsAndData<>(Optional.empty()));
            }
        });
    }

    public int partitionFor(String transactionalId) {
        return Utils.abs(transactionalId.hashCode()) % transactionTopicPartitionCount;
    }

    /**
     * When this broker becomes a leader for a transaction log partition, load this partition and populate the
     * transaction metadata cache with the transactional ids. This operation must be resilient to any partial state
     * left off from the previous loading / unloading operation.
     */
    public CompletableFuture<Void> loadTransactionsForTxnTopicPartition(
                                                                int partitionId,
                                                                SendTxnMarkersCallback sendTxnMarkers) {
        TopicPartition topicPartition = new TopicPartition(Topic.TRANSACTION_STATE_TOPIC_NAME, partitionId);

        CoreUtils.inWriteLock(stateLock, () -> {
            loadingPartitions.add(partitionId);
            transactionMetadataCache.putIfAbsent(topicPartition.partition(), Maps.newConcurrentMap());
            return null;
        });

        log.info("Start loading transaction metadata from {}.", topicPartition);
        long startTimeMs = SystemTime.SYSTEM.milliseconds();
        return getProducer(topicPartition.partition())
                .thenComposeAsync(producer ->
                        producer.newMessage().value(new byte[0]).sendAsync(), executor)
                .thenComposeAsync(lastMsgId -> {
                    if (log.isDebugEnabled()) {
                        log.debug("Successfully write a placeholder record into {} @ {}",
                                topicPartition, lastMsgId);
                    }
                    return getReader(topicPartition.partition()).thenComposeAsync(reader ->
                            loadTransactionMetadata(topicPartition.partition(), reader, lastMsgId), executor);
                }, executor)
                .thenComposeAsync(ignored ->
                        completeLoadedTransactions(topicPartition, startTimeMs, sendTxnMarkers), executor);
    }

    private CompletableFuture<Void> loadTransactionMetadata(int partition,
                                                            Reader<byte[]> reader,
                                                            MessageId lastMessageId) {
        if (log.isDebugEnabled()) {
            log.debug("Start load transaction metadata for partition {} till messageId {}", partition, lastMessageId);
        }
        CompletableFuture<Void> loadFuture = new CompletableFuture<>();
        Map<String, TransactionMetadata> transactionMetadataMap = new HashMap<>();
        loadNextTransaction(partition, reader, lastMessageId, loadFuture, transactionMetadataMap);
        return loadFuture;
    }

    private void loadNextTransaction(int partition,
                                     Reader<byte[]> reader,
                                     MessageId lastMessageId,
                                     CompletableFuture<Void> loadFuture,
                                     Map<String, TransactionMetadata> transactionMetadataMap) {
        reader.readNextAsync().whenComplete((message, throwable) -> {
            if (throwable != null) {
                log.error("Failed to load transaction log.", throwable);
                loadFuture.completeExceptionally(throwable);
            }
            if (message.getMessageId().compareTo(lastMessageId) >= 0) {
                // reach the end of partition
                transactionMetadataCache.put(partition, transactionMetadataMap);
                loadFuture.complete(null);
                return;
            }

            // skip place holder
            if (message.getKeyBytes() == null || message.getValue().length == 0) {
                loadNextTransaction(partition, reader, lastMessageId, loadFuture, transactionMetadataMap);
                return;
            }

            try {
                TransactionLogKey logKey = TransactionLogKey.decode(
                        Unpooled.wrappedBuffer(message.getKeyBytes()), TransactionLogKey.HIGHEST_SUPPORTED_VERSION);
                transactionMetadataMap.put(
                        logKey.getTransactionId(),
                        TransactionLogValue.readTxnRecordValue(logKey.getTransactionId(), message.getValue()));
                loadNextTransaction(partition, reader, lastMessageId, loadFuture, transactionMetadataMap);
            } catch (Exception e) {
                log.error("Failed to decode transaction log with message {} for partition {}.",
                        message.getMessageId(), partition, e);
                loadFuture.completeExceptionally(e);
            }
        });
    }

    private CompletableFuture<Void> completeLoadedTransactions(TopicPartition topicPartition, long startTimeMs,
                                            SendTxnMarkersCallback sendTxnMarkersCallback) {
        CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        Map<String, TransactionMetadata> loadedTransactions = transactionMetadataCache.get(topicPartition.partition());
        long endTimeMs = SystemTime.SYSTEM.milliseconds();
        long totalLoadingTimeMs = endTimeMs - startTimeMs;
        log.info("Finished loading {} transaction metadata from {} in {} milliseconds",
                loadedTransactions.size(), topicPartition, totalLoadingTimeMs);

        CoreUtils.inWriteLock(stateLock, () -> {
            if (loadingPartitions.contains(topicPartition.partition())) {
                List<TransactionalIdAndTransitMetadata> transactionsPendingForCompletion = new ArrayList<>();

                transactionMetadataCache.get(topicPartition.partition());
                for (Map.Entry<String, TransactionMetadata> entry : loadedTransactions.entrySet()) {
                    TransactionMetadata txnMetadata = entry.getValue();
                    txnMetadata.inLock(() -> {
                        switch (txnMetadata.getState()) {
                            case PREPARE_ABORT:
                                transactionsPendingForCompletion.add(
                                        new TransactionalIdAndTransitMetadata(
                                                entry.getKey(),
                                                TransactionResult.ABORT,
                                                txnMetadata,
                                                txnMetadata.prepareComplete(SystemTime.SYSTEM.milliseconds())
                                        ));
                                break;
                            case PREPARE_COMMIT:
                                transactionsPendingForCompletion.add(
                                        new TransactionalIdAndTransitMetadata(
                                                entry.getKey(),
                                                TransactionResult.COMMIT,
                                                txnMetadata,
                                                txnMetadata.prepareComplete(SystemTime.SYSTEM.milliseconds())
                                        ));
                                break;
                            default:
                                // no op
                        }
                        return null;
                    });
                }

                // We first remove the partition from loading partition then send out the markers for those pending to
                // be completed transactions, so that when the markers get sent the attempt of appending the complete
                // transaction log would not be blocked by the coordinator loading error.
                loadingPartitions.remove(topicPartition.partition());

                transactionsPendingForCompletion.forEach(pendingTxn -> {
                    sendTxnMarkersCallback.send(pendingTxn.result, pendingTxn.txnMetadata, pendingTxn.transitMetadata);
                });
            }
            loadingPartitions.remove(topicPartition.partition());
            return null;
        });

        log.info("Completed loading transaction metadata from {}", topicPartition);
        completableFuture.complete(null);
        return completableFuture;
    }

    public void removeTransactionsForTxnTopicPartition(int partition) {
        TopicPartition topicPartition = new TopicPartition(Topic.TRANSACTION_STATE_TOPIC_NAME, partition);
        log.info("Scheduling unloading transaction metadata from {}", topicPartition);
        executor.submit(() -> {
            CoreUtils.inWriteLock(stateLock, () -> {
                loadingPartitions.remove(partition);
                transactionMetadataCache.remove(partition).forEach((txnId, metadata) -> {
                    log.info("Unloaded transaction metadata {} for {} following local partition deletion",
                            metadata, topicPartition);
                });

                // remove related producers and readers
                CompletableFuture<Producer<byte[]>> producer = txnLogProducerMap.remove(partition);
                CompletableFuture<Reader<byte[]>> reader = txnLogReaderMap.remove(partition);
                if (producer != null) {
                    producer.thenApplyAsync(p -> p.closeAsync()).whenCompleteAsync((ignore, t) -> {
                        if (t != null) {
                            log.error("Failed to close producer when remove partition {}.",
                                    producer.join().getTopic());
                        }
                    }, executor);
                }
                if (reader != null) {
                    reader.thenApplyAsync(p -> p.closeAsync()).whenCompleteAsync((ignore, t) -> {
                        if (t != null) {
                            log.error("Failed to close reader when remove partition {}.",
                                    reader.join().getTopic());
                        }
                    }, executor);
                }
                return null;
            });
        });
    }

    interface SendTxnMarkersCallback {
        void send(TransactionResult transactionResult, TransactionMetadata transactionMetadata,
             TransactionMetadata.TxnTransitMetadata txnTransitMetadata);
    }

    private CompletableFuture<Producer<byte[]>> getProducer(Integer partition) {
        return txnLogProducerMap.computeIfAbsent(partition, key -> {
            String topic = transactionConfig.getTransactionMetadataTopicName()
                    + TopicName.PARTITIONED_TOPIC_SUFFIX + partition;
            return pulsarClient.newProducer().topic(topic).createAsync();
        });
    }

    private CompletableFuture<MessageId> storeTxnLog(String transactionalId,
                                                     TransactionMetadata.TxnTransitMetadata txnTransitMetadata) {
        byte[] keyBytes = new TransactionLogKey(transactionalId).toBytes();
        byte[] valueBytes = new TransactionLogValue(txnTransitMetadata).toBytes();
        return getProducer(partitionFor(transactionalId)).thenComposeAsync(producer ->
                producer.newMessage().keyBytes(keyBytes).value(valueBytes).sendAsync(), executor);
    }

    private CompletableFuture<Reader<byte[]>> getReader(Integer partition) {
        return txnLogReaderMap.computeIfAbsent(partition, key -> {
            String topic = transactionConfig.getTransactionMetadataTopicName()
                    + TopicName.PARTITIONED_TOPIC_SUFFIX + partition;
            return pulsarClient.newReader().topic(topic)
                    .startMessageId(MessageId.earliest).readCompacted(true).createAsync();
        });
    }

}
