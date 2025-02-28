/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic.v2;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.kroxylicious.testing.kafka.api.KafkaCluster;
import io.kroxylicious.testing.kafka.common.BrokerCluster;
import io.kroxylicious.testing.kafka.junit5ext.KafkaClusterExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.api.kafka.model.KafkaTopicBuilder;
import io.strimzi.operator.common.MetricsProvider;
import io.strimzi.operator.common.MicrometerMetricsProvider;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.metrics.MetricsHolder;
import io.strimzi.operator.common.metrics.OperatorMetricsHolder;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigsResult;
import org.apache.kafka.clients.admin.CreatePartitionsResult;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.clients.admin.DescribeConfigsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListPartitionReassignmentsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicCollection;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static io.strimzi.api.kafka.model.KafkaTopic.RESOURCE_KIND;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * This test is not intended to provide lots of coverage of the {@link BatchingTopicController}
 * (see {@link TopicControllerIT} for that), rather it aims to cover some parts that a difficult
 * to test via {@link TopicControllerIT}
 */
@ExtendWith(KafkaClusterExtension.class)
class BatchingTopicControllerTest {

    private static final Logger LOGGER = LogManager.getLogger(BatchingTopicControllerTest.class);

    static final String NAMESPACE = "ns";
    static final String NAME = "foo";

    private BatchingTopicController controller;
    private KubernetesClient client;

    private Admin[] admin = new Admin[] {null};

    private MetricsHolder metrics;

    private static <T> KafkaFuture<T> interruptedFuture() throws ExecutionException, InterruptedException {
        var future = mock(KafkaFuture.class);
        doThrow(new InterruptedException()).when(future).get();
        return future;
    }

    private String namespace(String ns) {
        return TopicOperatorTestUtil.namespace(client, ns);
    }

    private KafkaTopic createResource() {
        var kt = Crds.topicOperation(client).resource(new KafkaTopicBuilder().withNewMetadata()
                .withName(NAME)
                .withNamespace(namespace(NAMESPACE))
                .addToLabels("key", "VALUE")
                .endMetadata()
                .withNewSpec()
                .withPartitions(2)
                .withReplicas(1)
                .endSpec().build()).create();
        return kt;
    }

    @BeforeAll
    public static void setupKubeCluster() {
        TopicOperatorTestUtil.setupKubeCluster(NAMESPACE);
    }

    @AfterAll
    public static void teardownKubeCluster() {
        TopicOperatorTestUtil.teardownKubeCluster2(NAMESPACE);
    }

    @BeforeEach
    public void beforeEach() {
        this.client = new KubernetesClientBuilder().build();
        MetricsProvider metricsProvider = new MicrometerMetricsProvider(new SimpleMeterRegistry());
        this.metrics = new OperatorMetricsHolder(RESOURCE_KIND, null, metricsProvider);
    }

    @AfterEach
    public void after(TestInfo testInfo) throws InterruptedException {
        LOGGER.debug("Cleaning up after test {}", TopicOperatorTestUtil.testName(testInfo));
        if (admin[0] != null) {
            admin[0].close();
        }

        String pop = NAMESPACE;
        TopicOperatorTestUtil.cleanupNamespace(client, testInfo, pop);

        client.close();
        LOGGER.debug("Cleaned up after test {}", TopicOperatorTestUtil.testName(testInfo));
    }

    private void assertOnUpdateThrowsInterruptedException(KubernetesClient client, Admin admin, KafkaTopic kt) throws ExecutionException, InterruptedException {
        controller = new BatchingTopicController(Map.of("key", "VALUE"), admin, client, true, metrics, NAMESPACE);
        List<ReconcilableTopic> batch = List.of(new ReconcilableTopic(new Reconciliation("test", "KafkaTopic", NAMESPACE, NAME), kt, BatchingTopicController.topicName(kt)));
        assertThrows(InterruptedException.class, () -> controller.onUpdate(batch));
    }


    @Test
    public void shouldHandleInterruptedExceptionFromDescribeTopics(KafkaCluster cluster) throws ExecutionException, InterruptedException {
        admin[0] = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.getBootstrapServers()));
        Admin adminSpy = Mockito.spy(admin[0]);
        var result = Mockito.mock(DescribeTopicsResult.class);
        Mockito.doReturn(interruptedFuture()).when(result).allTopicNames();
        Mockito.doReturn(Map.of(NAME, interruptedFuture())).when(result).topicNameValues();
        Mockito.doReturn(result).when(adminSpy).describeTopics(any(Collection.class));

        KafkaTopic kt = createResource();
        assertOnUpdateThrowsInterruptedException(client, adminSpy, kt);
    }

    @Test
    public void shouldHandleInterruptedExceptionFromDescribeConfigs(KafkaCluster cluster) throws ExecutionException, InterruptedException {
        admin[0] = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.getBootstrapServers()));
        Admin adminSpy = Mockito.spy(admin[0]);
        var result = Mockito.mock(DescribeConfigsResult.class);
        Mockito.doReturn(interruptedFuture()).when(result).all();
        Mockito.doReturn(Map.of(topicConfigResource(), interruptedFuture())).when(result).values();
        Mockito.doReturn(result).when(adminSpy).describeConfigs(Mockito.argThat(a -> a.stream().anyMatch(x -> x.type() == ConfigResource.Type.TOPIC)));

        KafkaTopic kt = createResource();
        assertOnUpdateThrowsInterruptedException(client, adminSpy, kt);
    }

    private static ConfigResource topicConfigResource() {
        return new ConfigResource(ConfigResource.Type.TOPIC, NAME);
    }

    @Test
    public void shouldHandleInterruptedExceptionFromCreateTopics(KafkaCluster cluster) throws ExecutionException, InterruptedException {
        admin[0] = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.getBootstrapServers()));
        Admin adminSpy = Mockito.spy(admin[0]);
        var result = Mockito.mock(CreateTopicsResult.class);
        Mockito.doReturn(interruptedFuture()).when(result).all();
        Mockito.doReturn(Map.of(NAME, interruptedFuture())).when(result).values();
        Mockito.doReturn(result).when(adminSpy).createTopics(any());

        KafkaTopic kt = createResource();
        assertOnUpdateThrowsInterruptedException(client, adminSpy, kt);
    }

    @Test
    public void shouldHandleInterruptedExceptionFromIncrementalAlterConfigs(KafkaCluster cluster) throws ExecutionException, InterruptedException {
        admin[0] = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.getBootstrapServers()));
        admin[0].createTopics(List.of(new NewTopic(NAME, 1, (short) 1).configs(Map.of(TopicConfig.COMPRESSION_TYPE_CONFIG, "snappy")))).all().get();
        Admin adminSpy = Mockito.spy(admin[0]);
        var result = Mockito.mock(AlterConfigsResult.class);
        Mockito.doReturn(interruptedFuture()).when(result).all();
        Mockito.doReturn(Map.of(topicConfigResource(), interruptedFuture())).when(result).values();
        Mockito.doReturn(result).when(adminSpy).incrementalAlterConfigs(any());
        KafkaTopic kt = createResource();
        assertOnUpdateThrowsInterruptedException(client, adminSpy, kt);
    }

    @Test
    public void shouldHandleInterruptedExceptionFromCreatePartitions(KafkaCluster cluster) throws ExecutionException, InterruptedException {
        admin[0] = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.getBootstrapServers()));
        admin[0].createTopics(List.of(new NewTopic(NAME, 1, (short) 1))).all().get();

        Admin adminSpy = Mockito.spy(admin[0]);
        var result = Mockito.mock(CreatePartitionsResult.class);
        Mockito.doReturn(interruptedFuture()).when(result).all();
        Mockito.doReturn(Map.of(NAME, interruptedFuture())).when(result).values();
        Mockito.doReturn(result).when(adminSpy).createPartitions(any());
        KafkaTopic kt = createResource();
        assertOnUpdateThrowsInterruptedException(client, adminSpy, kt);
    }

    @Test
    public void shouldHandleInterruptedExceptionFromListReassignments(
            @BrokerCluster(numBrokers = 2)
            KafkaCluster cluster) throws ExecutionException, InterruptedException {
        admin[0] = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.getBootstrapServers()));
        admin[0].createTopics(List.of(new NewTopic(NAME, 2, (short) 2))).all().get();

        Admin adminSpy = Mockito.spy(admin[0]);
        var result = Mockito.mock(ListPartitionReassignmentsResult.class);
        Mockito.doReturn(interruptedFuture()).when(result).reassignments();
        Mockito.doReturn(result).when(adminSpy).listPartitionReassignments(any(Set.class));
        KafkaTopic kt = createResource();
        assertOnUpdateThrowsInterruptedException(client, adminSpy, kt);
    }

    @Test
    public void shouldHandleInterruptedExceptionFromDeleteTopics(KafkaCluster cluster) throws ExecutionException, InterruptedException {
        admin[0] = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.getBootstrapServers()));
        admin[0].createTopics(List.of(new NewTopic(NAME, 1, (short) 1))).all().get();
        var kt = createResource();
        var withFinalizer = Crds.topicOperation(client).inNamespace(NAMESPACE).withName(NAME).edit(theKt -> {
            return new KafkaTopicBuilder(theKt).editOrNewMetadata().addToFinalizers(BatchingTopicController.FINALIZER).endMetadata().build();
        });
        Crds.topicOperation(client).inNamespace(NAMESPACE).withName(NAME).delete();
        var withDeletionTimestamp = Crds.topicOperation(client).inNamespace(NAMESPACE).withName(NAME).get();

        Admin adminSpy = Mockito.spy(admin[0]);
        var result = Mockito.mock(DeleteTopicsResult.class);
        Mockito.doReturn(interruptedFuture()).when(result).all();
        Mockito.doReturn(Map.of(NAME, interruptedFuture())).when(result).topicNameValues();
        Mockito.doReturn(result).when(adminSpy).deleteTopics(any(TopicCollection.TopicNameCollection.class));
        assertOnUpdateThrowsInterruptedException(client, adminSpy, withDeletionTimestamp);
    }

    // TODO kube client interrupted exceptions


}