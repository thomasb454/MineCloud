/*
 * Copyright (c) 2015, Mazen Kotb <email@mazenmc.io>
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package io.minecloud.controller;

import io.minecloud.MineCloud;
import io.minecloud.db.Credentials;
import io.minecloud.db.mongo.MongoDatabase;
import io.minecloud.db.redis.RedisDatabase;
import io.minecloud.db.redis.msg.binary.MessageOutputStream;
import io.minecloud.db.redis.pubsub.SimpleRedisChannel;
import io.minecloud.models.bungee.type.BungeeType;
import io.minecloud.models.network.Network;
import io.minecloud.models.nodes.Node;
import io.minecloud.models.nodes.type.NodeType;
import io.minecloud.models.server.Server;
import io.minecloud.models.server.ServerRepository;
import io.minecloud.models.server.type.ServerType;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.stream.IntStream;

public class Controller {
    private static Controller instance;

    private final RedisDatabase redis;
    private final MongoDatabase mongo;

    private Controller() {
        instance = this;

        this.redis = MineCloud.instance().redis();
        this.mongo = MineCloud.instance().mongo();

        redis.addChannel(SimpleRedisChannel.create("bungee-create", redis));
        redis.addChannel(SimpleRedisChannel.create("server-create", redis));

        while (!Thread.currentThread().isInterrupted()) {
            mongo.repositoryBy(Network.class).models()
                    .forEach((network) -> {

                        network.bungeeMetadata().forEach((type, amount) -> {
                            int difference = amount - network.bungeesOnline(type);

                            if (difference > 0) {
                                IntStream.range(0, difference)
                                        .forEach((i) -> deployBungee(network, type));
                            }
                        });

                        network.serverMetadata().forEach((metadata) -> {
                            int serversOnline = network.serversOnline(metadata.type());

                            int newServers = metadata.minimumAmount() - serversOnline;
                            int space = metadata.type().maxPlayers() * serversOnline;
                            ServerRepository repository = mongo.repositoryBy(Server.class);

                            List<Server> servers = repository.find(repository.createQuery()
                                    .field("type").equal(metadata.type())
                                    .field("network").equal(network))
                                    .asList();
                            int onlinePlayers = servers.stream()
                                    .flatMapToInt((s) -> IntStream.of(s.onlinePlayers().size()))
                                    .sum();

                            System.out.println(newServers + "," + space + "," + onlinePlayers);

                            if (onlinePlayers > (space * 0.75)) {
                                newServers += (int) Math.round(onlinePlayers / (space * 0.75)) + 1;
                            }

                            if ((newServers + servers.size()) > metadata.maximumAmount()) {
                                newServers = metadata.maximumAmount() - servers.size();
                            }

                            if (newServers > 0) {
                                IntStream.range(0, newServers)
                                        .forEach((i) -> deployServer(network, metadata.type()));
                            }
                        });
                    });

            try {
                Thread.sleep(10000L);
            } catch (InterruptedException ignored) {
                // I don't care
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        File configFolder = new File("/etc/minecloud/controller/");
        File file = new File(configFolder, "details.properties");

        if (!configFolder.exists()) {
            configFolder.mkdirs();
        }

        if (!file.exists()) {
            file.createNewFile();
        }

        properties.load(new FileInputStream(file));

        if (!properties.containsKey("mongo-hosts")) {
            MineCloud.runSetup(properties, file);
            new Controller();
        }

        Credentials mongo = new Credentials(properties.getProperty("mongo-hosts").split(";"),
                properties.getProperty("mongo-username"),
                properties.getProperty("mongo-password").toCharArray(),
                properties.getProperty("mongo-database"));
        Credentials redis = new Credentials(new String[] {properties.getProperty("redis-host")},
                properties.getProperty("redis-username"),
                properties.getProperty("redis-password").toCharArray());

        MineCloud.instance().initiateMongo(mongo);
        MineCloud.instance().initiateRedis(redis);

        new Controller();
    }

    public static Controller instance() {
        return instance;
    }

    public void deployBungee(Network network, BungeeType type) {
        MessageOutputStream os = new MessageOutputStream();
        Node node = findNode(network, type.preferredNode(), type.dedicatedRam());

        try {
            os.writeString(node.name());
            os.writeString(network.name());
            os.writeString(type.name());
        } catch (IOException e) {
            MineCloud.logger().log(Level.SEVERE, "Encountered an odd exception whilst encoding a message", e);
            return;
        }

        MineCloud.logger().info("Sent deploy message to " + node.name() + " for bungee type " + type.name());
        redis.channelBy("bungee-create").publish(os.toMessage());
    }

    public void deployServer(Network network, ServerType type) {
        MessageOutputStream os = new MessageOutputStream();
        Node node = findNode(network, type.preferredNode(), type.dedicatedRam());

        try {
            os.writeString(node.name());
            os.writeString(network.name());
            os.writeString(type.name());
        } catch (IOException e) {
            MineCloud.logger().log(Level.SEVERE, "Encountered an odd exception whilst encoding a message", e);
            return;
        }

        MineCloud.logger().info("Sent deploy message to " + node.name() + " for server type " + type.name());
        redis.channelBy("server-create").publish(os.toMessage());
    }

    public Node findNode(Network network, NodeType preferredNode, int requiredRam) {
        Node selectedNode = null;

        for (Node node : network.nodes()) {
            if (selectedNode == null && node.availableRam() >= requiredRam) {
                selectedNode = node;
                continue;
            }

            if (selectedNode == null) {
                continue;
            }

            // TODO allow plugins to interfere with this process
            double usageDifference = selectedNode.totalUsage() - node.totalUsage();

            if (usageDifference > 0) {
                double ramDifference = node.availableRam() - selectedNode.availableRam();

                if (ramDifference > 0) {
                    selectedNode = node;
                } else if (ramDifference >= -nodeMemoryThreshold(node) &&
                        isPreferredNode(node, selectedNode, preferredNode)) {
                    selectedNode = node;
                }
            } else if (usageDifference >= -200 && // TODO configurable threshold
                    isPreferredNode(node, selectedNode, preferredNode)) {
                selectedNode = node;
            }
        }

        return selectedNode;
    }

    private double nodeMemoryThreshold(Node node) {
        return (node.availableRam() / (node.serverCount() + 1)); // TODO configurable
    }

    private boolean isPreferredNode(Node node, Node currentNode, NodeType preferred) {
        return node.type().equals(preferred) && !currentNode.type().equals(preferred);
    }
}
