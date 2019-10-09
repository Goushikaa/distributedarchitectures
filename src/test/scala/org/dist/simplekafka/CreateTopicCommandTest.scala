package org.dist.simplekafka

import org.scalatest.FunSuite

class TestZookeeperClient(brokerIds:List[Int]) extends ZookeeperClient {
  var topicName:String = null
  var partitionReplicas = Set[PartitionReplicas]()
  override def getAllBrokerIds(): List[Int] = List(0, 1, 2)

  override def setPartitionReplicasForTopic(topicName: String, partitionReplicas: Set[PartitionReplicas]): Unit = {
    this.topicName = topicName
    this.partitionReplicas = partitionReplicas
  }

  override def registerSelf(): Unit = {}
}

class CreateTopicCommandTest extends FunSuite {
  test("should assign set of replicas for partitions of topic") {
    val brokerIds = List(0, 1, 2)
    val zookeeperClient = new TestZookeeperClient(brokerIds)
    val createCommandTest = new CreateTopicCommand(zookeeperClient)
    val noOfPartitions = 3
    val replicationFactor = 2
    createCommandTest.createTopic("topic1", noOfPartitions, replicationFactor)
    assert(zookeeperClient.topicName == "topic1")
    assert(zookeeperClient.partitionReplicas.size == noOfPartitions)
    zookeeperClient.partitionReplicas.map(p ⇒ p.brokerIds).foreach(_.size == replicationFactor)
  }
}
