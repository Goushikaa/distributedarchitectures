package org.dist.consensus.zab

import java.net.{ServerSocket, Socket}
import java.util
import java.util.concurrent.ConcurrentLinkedQueue

import org.dist.queue.common.Logging

import scala.jdk.CollectionConverters._

object Leader {
  /**
   * This message type is sent by a leader to propose a mutation.
   */
  val PROPOSAL = 2
  /**
   * This message is for follower to expect diff
   */
  private[zab] val DIFF = 13
  /**
   * This is for follower to truncate its logs
   */
  private[zab] val TRUNC = 14
  /**
   * This is for follower to download the snapshots
   */
  private[zab] val SNAP = 15
  /**
   * This message type is sent by the leader to indicate it's zxid and if
   * needed, its database.
   */
  private[zab] val NEWLEADER = 10
  /**
   * This message type is sent by a follower to indicate the last zxid in its
   * log.
   */
  private[zab] val LASTZXID = 11
  /**
   * This message type is sent by the leader to indicate that the follower is
   * now uptodate andt can start responding to clients.
   */
  private[zab] val UPTODATE = 12
  /**
   * This message type is sent to a leader to request and mutation operation.
   * The payload will consist of a request header followed by a request.
   */
  private[zab] val REQUEST = 1
  /**
   * This message type is sent by a follower after it has synced a proposal.
   */
  private[zab] val ACK = 3

  /**
   * This message type is sent by a leader to commit a proposal and cause
   * followers to start serving the corresponding data.
   */
  private[zab] val COMMIT = 4

  /**
   * This message type is enchanged between follower and leader (initiated by
   * follower) to determine liveliness.
   */
  private[zab] val PING = 5

  /**
   * This message type is to validate a session that should be active.
   */
  private[zab] val REVALIDATE = 6

  /**
   * This message is a reply to a synchronize command flushing the pipe
   * between the leader and the follower.
   */
  private[zab] val SYNC = 7
}

class SocketConnect {}

case class Request(socketConnect:SocketConnect, requestType:Int, xid:Int, data:Array[Byte])

case class Proposal(val packet: QuorumPacket, var ackCount: Int = 0, val request: Option[Request] = None)

class Leader(self: QuorumPeer) extends Logging {
  def startForwarding(handler: FollowerHandler, lastSeenZxid: Long) = {
    if (lastProposed > lastSeenZxid) {
      for (p <- toBeApplied.asScala) {
        if (p.packet.zxid > lastSeenZxid) // continue todo: continue is not supported
          handler.queuePacket(p.packet)
        // Since the proposal has been committed we need to send the
        // commit message
        // also
        val qp = new QuorumPacket(Leader.COMMIT, p.packet.zxid)
        handler.queuePacket(qp)
      }
      for (p <- outstandingProposals.asScala) {
        if (p.packet.zxid > lastSeenZxid)  //continue todo: continue is not supported
        handler.queuePacket(p.packet)
      }
    }
    followers.add(handler)
    lastProposed

  }


  val toBeApplied = new ConcurrentLinkedQueue[Proposal]

  def processAck(zxid: Long, followerSocket: Socket): Unit = this.synchronized {
    if (outstandingProposals.size == 0)
      return

    if (outstandingProposals.peek.packet.zxid > zxid) { // The proposal has already been committed
      return
    }

    outstandingProposals.asScala.foreach(p ⇒ {
      info(s"Handling ACK for zxid ${zxid} for packet ${p}")
      if (p.packet.zxid == zxid) {
        p.ackCount += 1
        if (p.ackCount > self.config.servers.size / 2) {
          //          if (!first) {
          //            LOG.error("Commiting " + Long.toHexString(zxid)
          //              + " from " + followerAddr + " not first!");
          //            LOG.error("First is "
          //              + outstandingProposals.element().packet);
          //            System.exit(13);
          //          }
          //          outstandingProposals.remove();
          if (p.request != null) toBeApplied.add(p)
          // We don't commit the new leader proposal
          if ((zxid & 0xffffffffL) != 0) {
            if (p.request == null) error("Going to commmit null: " + p)
//            commit(zxid)
//            zk.commitProcessor.commit(p.request)
          }
        }
      }
    })

  }


  @volatile var lastProposed:Long = 0L
  val config = self.config

  private val leaderLastZxid: Long = 0
  private val newLeaderProposal =  Proposal(QuorumPacket(Leader.NEWLEADER, leaderLastZxid, Array[Byte]()), 0)


  val ss = new ServerSocket(config.serverAddress.port, 100, config.serverAddress.address)
  val followers = new util.ArrayList[FollowerHandler]()
  val outstandingProposals = new ConcurrentLinkedQueue[Proposal]
  private var zk = new LeaderZookeeperServer(self)

  def shutdown(str: String) = {
        //TODO
  }

  def lead(): Any = {
    var epoch: Long = self.getLastLoggedZxid >> 32L
    epoch += 1
    zk.setZxid(epoch << 32L)
    zk.dataTree.lastProcessedZxid = zk.getZxid()
    lastProposed = zk.getZxid()
    if ((newLeaderProposal.packet.zxid & 0xffffffffL) != 0) {
      error("NEWLEADER proposal has Zxid of " + newLeaderProposal.packet.zxid)
    }
    outstandingProposals.add(newLeaderProposal)

    //setup tcpip server communication
    //set processing pipeline
    new Thread() {
      override def run(): Unit = {
        try {
          info(s"Leader listening on ${config.serverAddress.address} and ${config.serverAddress.port}")
          while (true) {
            val s = ss.accept
            s.setSoTimeout(config.tickTime * config.syncLimit)
            s.setTcpNoDelay(true)
            val followerHandler = new FollowerHandler(s, Leader.this)
            followerHandler.start()
          }
        } catch {
          case e: Exception ⇒ {
            error("Error in follower connection. Moving ahead")
          }
          //
        }
      }
    }.start()

    newLeaderProposal.ackCount += 1
    newLeaderProposal.packet.zxid = zk.getZxid()
    while (newLeaderProposal.ackCount <= self.config.servers.size / 2) {
      info("Waiting for quorum to send ack for NEWLEADER requests")
      if (self.tick.get() > self.config.initLimit) { // Followers aren't syncing fast enough,
        // renounce leadership!
        shutdown("Waiting for " + (self.config.servers.size / 2) + " followers, only synced with " + newLeaderProposal.ackCount)
        if (followers.size >= self.config.servers.size / 2) warn("Enough followers present. " + "Perhaps the initTicks need to be increased.")
        return
      }
      Thread.sleep(self.config.tickTime)
      self.tick.incrementAndGet()
    }

    info("Leader got ACKs from Quorum of servers. Ready to lead now")

    while (true) {
      Thread.sleep(self.config.tickTime / 2)

      self.tick.incrementAndGet()

      followers.asScala.foreach(follower ⇒ {
        follower.ping()
      })
    }
  }

}
