package org.dist.kvstore

import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.util

import scala.collection.JavaConverters._

class TcpListener(localEp: InetAddressAndPort, gossiper: Gossiper, messagingService: MessagingService) extends Thread {

  override def run(): Unit = {
    val serverSocket = new ServerSocket()
    serverSocket.bind(new InetSocketAddress(localEp.address, localEp.port))
    println(s"Listening on ${localEp}")
    while (true) {
      val socket = serverSocket.accept()
      socket.setSoTimeout(1000)
      val inputStream = socket.getInputStream()
      val messageBytes = inputStream.readAllBytes()

      val message = JsonSerDes.deserialize(messageBytes, classOf[Message])

      println(s"Got message ${message}")

      if(message.header.verb == Verb.GOSSIP_DIGEST_SYN) {
        new GossipDigestSynHandler(gossiper, messagingService).handleMessage(message)

      } else if (message.header.verb == Verb.GOSSIP_DIGEST_ACK) {
        new GossipDigestSynAckHandler(gossiper, messagingService).handleMessage(message)

      } else if (message.header.verb == Verb.GOSSIP_DIGEST_ACK2) {
        new GossipDigestAck2Handler(gossiper, messagingService).handleMessage(message)
      }


      inputStream.close()
      socket.close()
    }
  }

  class GossipDigestSynHandler(gossiper: Gossiper, messagingService: MessagingService) {
    def handleMessage(synMessage: Message): Unit = {
      val gossipDigestSyn = JsonSerDes.deserialize(synMessage.payloadJson.getBytes, classOf[GossipDigestSyn])

      val deltaGossipDigest = new util.ArrayList[GossipDigest]()
      val deltaEndPointStates = new util.HashMap[InetAddressAndPort, EndPointState]()
      gossiper.examineGossiper(gossipDigestSyn.gDigests, deltaGossipDigest, deltaEndPointStates)

      val synAckMessage = new gossiper.GossipSynAckMessageBuilder().makeGossipDigestAckMessage(deltaGossipDigest, deltaEndPointStates)
      messagingService.sendTcpOneWay(synAckMessage, synMessage.header.from)
    }
  }


  class GossipDigestSynAckHandler(gossiper: Gossiper, messagingService: MessagingService) {
    def handleMessage(synAckMessage: Message): Unit = {
      val gossipDigestSynAck = JsonSerDes.deserialize(synAckMessage.payloadJson.getBytes, classOf[GossipDigestAck])
      val epStateMap = gossipDigestSynAck.stateMap.asJava
      if (epStateMap.size() > 0) {
        gossiper.notifyFailureDetector(epStateMap)
        gossiper.applyStateLocally(epStateMap)
      }

      /* Get the state required to send to this gossipee - construct GossipDigestAck2Message */
      val deltaEpStateMap = new util.HashMap[InetAddressAndPort, EndPointState]

      for (gDigest <- gossipDigestSynAck.digestList) {
        val addr = gDigest.endPoint
        val localEpStatePtr = gossiper.getStateForVersionBiggerThan(addr, gDigest.maxVersion)
        if (localEpStatePtr != null) deltaEpStateMap.put(addr, localEpStatePtr)
      }

      val ack2Message = new gossiper.GossipAck2MessageBuilder().makeGossipDigestAck2Message(deltaEpStateMap)
      messagingService.sendTcpOneWay(ack2Message, synAckMessage.header.from)
    }
  }

  class GossipDigestAck2Handler(gossiper: Gossiper, messagingService: MessagingService) {
    def handleMessage(ack2Message: Message): Unit = {
      val gossipDigestAck2 = JsonSerDes.deserialize(ack2Message.payloadJson.getBytes, classOf[GossipDigestAck2])
      val epStateMap = gossipDigestAck2.stateMap
      gossiper.notifyFailureDetector(epStateMap)
      gossiper.applyStateLocally(epStateMap)
    }
  }
}

class MessagingService() {
  var gossiper:Gossiper = _

  def init(gossiper:Gossiper): Unit = {
    this.gossiper = gossiper
  }

  def listen(localEp: InetAddressAndPort): Unit = {
    assert(gossiper != null)
    new TcpListener(localEp, gossiper, this).start()
  }

  def sendTcpOneWay(message: Message, to: InetAddressAndPort) = {
    val clientSocket = new Socket(to.address, to.port)
    clientSocket.setSoTimeout(1000)
    try {
      val serializedMessage = JsonSerDes.serialize(message)
      val outputStream = clientSocket.getOutputStream()
      outputStream.write(serializedMessage.getBytes)
      outputStream.flush()
      outputStream.close()

    } catch {

      case e:Exception => e.printStackTrace()

    } finally {
      clientSocket.close()
    }
  }

  def sendUdpOneWay(message: Message, to: InetAddressAndPort) = {

  }

}
