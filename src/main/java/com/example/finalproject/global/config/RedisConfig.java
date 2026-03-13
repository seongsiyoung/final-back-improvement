package com.example.finalproject.global.config;

import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import java.time.Duration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisKeyValueAdapter.EnableKeyspaceEvents;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;

@Configuration
@EnableRedisRepositories(enableKeyspaceEvents = EnableKeyspaceEvents.ON_STARTUP, keyspaceNotificationsConfigParameter = "")
public class RedisConfig {
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(RedisProperties redisProperties) {

        if (redisProperties.getCluster() != null &&
                redisProperties.getCluster().getNodes() != null &&
                !redisProperties.getCluster().getNodes().isEmpty()) {

            RedisClusterConfiguration clusterConfig =
                    new RedisClusterConfiguration(redisProperties.getCluster().getNodes());

            ClusterTopologyRefreshOptions topologyRefreshOptions =
                    ClusterTopologyRefreshOptions.builder()
                            .enableAdaptiveRefreshTrigger(
                                    ClusterTopologyRefreshOptions.RefreshTrigger.MOVED_REDIRECT,
                                    ClusterTopologyRefreshOptions.RefreshTrigger.PERSISTENT_RECONNECTS)
                            .adaptiveRefreshTriggersTimeout(Duration.ofSeconds(30))
                            .enablePeriodicRefresh(Duration.ofMinutes(5))
                            .build();

            ClusterClientOptions clientOptions = ClusterClientOptions.builder()
                    .topologyRefreshOptions(topologyRefreshOptions)
                    .build();

            LettuceClientConfiguration clientConfig =
                    LettuceClientConfiguration.builder()
                            .clientOptions(clientOptions)
                            .useSsl()
                            .build();

            return new LettuceConnectionFactory(clusterConfig, clientConfig);
        }

        RedisStandaloneConfiguration standaloneConfig =
                new RedisStandaloneConfiguration(
                        redisProperties.getHost(),
                        redisProperties.getPort()
                );

        if (redisProperties.getPassword() != null) {
            standaloneConfig.setPassword(redisProperties.getPassword());
        }

        return new LettuceConnectionFactory(standaloneConfig);
    }
}

