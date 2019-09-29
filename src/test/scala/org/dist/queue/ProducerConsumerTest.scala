package org.dist.queue

import org.dist.kvstore.{InetAddressAndPort, JsonSerDes}
import org.dist.queue.api.{RequestKeys, RequestOrResponse, TopicMetadataRequest}
import org.dist.queue.utils.ZkUtils

class ProducerConsumerTest extends ZookeeperTestHarness {

  test("should register broker to zookeeper on startup") {
    val brokerId1 = 0
    val brokerId2 = 1
    val brokerId3 = 2



    val config1 = Config(brokerId1, TestUtils.hostName(), TestUtils.choosePort(), TestZKUtils.zookeeperConnect, List(TestUtils.tempDir().getAbsolutePath))
    val server1 = new Server(config1)
    server1.startup()

    val config2 = Config(brokerId2, TestUtils.hostName(), TestUtils.choosePort(), TestZKUtils.zookeeperConnect, List(TestUtils.tempDir().getAbsolutePath))
    val server2 = new Server(config2)
    server2.startup()

    val config3 = Config(brokerId3, TestUtils.hostName(), TestUtils.choosePort(), TestZKUtils.zookeeperConnect, List(TestUtils.tempDir().getAbsolutePath))
    val server3 = new Server(config3)
    server3.startup()

    val zkClient = KafkaZookeeperClient.getZookeeperClient(config1)

    val brokers: collection.Seq[ZkUtils.Broker] = ZkUtils.getAllBrokersInCluster(zkClient)

    assert(3 == brokers.size)

    val sortedBrokers = brokers.sortWith((b1, b2) ⇒ b1.id < b2.id)

    assert(sortedBrokers(0).id == config1.brokerId)
    assert(sortedBrokers(0).host == config1.hostName)
    assert(sortedBrokers(0).port == config1.port)

    assert(sortedBrokers(1).id == config2.brokerId)
    assert(sortedBrokers(1).host == config2.hostName)
    assert(sortedBrokers(1).port == config2.port)

    CreateTopicCommand.createTopic(zkClient, "topic1", 2, 2)


    val str = JsonSerDes.serialize(TopicMetadataRequest(RequestKeys.MetadataKey, 1, "client1", Seq("topic1")))
    val medataRequest = RequestOrResponse(RequestKeys.MetadataKey, str, 1)

    println()
    println("************* waiting till metadata is propagated *********")
    Thread.sleep(2000)

    val bootstrapBroker = InetAddressAndPort.create(config1.hostName, config1.port)
    val producer = new Producer(bootstrapBroker, config1, new DefaultPartitioner[String]())
    producer.send(KeyedMessage("topic1", "key1", "test message"))
    producer.send(KeyedMessage("topic1", "key2", "test message2"))
    producer.send(KeyedMessage("topic1", "key3", "test message3"))

    Thread.sleep(5000) //FIXME: Need to figure out why we need to do this

    val messages = new Consumer(bootstrapBroker, config1).read("topic1", 0)
    println("***************************** Received messages on topic1 ***************************")
    messages.foreach(keyedMessage⇒{
      println(s"${keyedMessage.key} => ${keyedMessage.message}")
    })

    server1.awaitShutdown()
    server2.awaitShutdown()
//    Thread.sleep(100000)
  }
}
