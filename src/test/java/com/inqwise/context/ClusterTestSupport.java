package com.inqwise.context;

import java.util.concurrent.TimeUnit;

import com.hazelcast.config.Config;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.eventbus.EventBusOptions;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;

final class ClusterTestSupport {
    private ClusterTestSupport() {
    }

    static Future<Vertx> startNode(HazelcastClusterManager manager) {
        return Vertx.builder().with(new VertxOptions().setEventLoopPoolSize(2).setWorkerPoolSize(2)
            .setEventBusOptions(new EventBusOptions().setHost("127.0.0.1").setPort(0)))
            .withClusterManager(manager).buildClustered();
    }

    static Config clusterConfig(String clusterName, String seed) {
        Config config = new Config().setClusterName(clusterName);
        config.setProperty("hazelcast.logging.type", "log4j2");
        config.setProperty("hazelcast.operation.thread.count", "2");
        config.setProperty("hazelcast.operation.generic.thread.count", "2");
        config.setProperty("hazelcast.io.input.thread.count", "1");
        config.setProperty("hazelcast.io.output.thread.count", "1");
        config.setProperty("hazelcast.event.thread.count", "1");
        config.setProperty("hazelcast.wait.seconds.before.join", "0");
        config.getMetricsConfig().setEnabled(false);
        var network = config.getNetworkConfig().setPort(0).setPortAutoIncrement(false);
        network.getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        network.getJoin().getMulticastConfig().setEnabled(false);
        network.getJoin().getAutoDetectionConfig().setEnabled(false);
        var tcp = network.getJoin().getTcpIpConfig().setEnabled(true);
        if (seed != null) {
            tcp.addMember(seed).setRequiredMember(seed);
        }
        return config;
    }

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(25, TimeUnit.SECONDS);
    }
}
