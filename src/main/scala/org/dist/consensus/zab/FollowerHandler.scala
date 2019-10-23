package org.dist.consensus.zab

import java.io._
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

import org.dist.queue.common.Logging

import scala.util.control.Breaks

class BinaryOutputArchive(val os: OutputStream) {
  val ds = new DataOutputStream(os)

  def writeRecord(p: QuorumPacket): Unit = {
    ds.writeInt(p.recordType)
    ds.writeLong(p.zxid)
    ds.writeInt(p.data.size)
    ds.write(p.data)
    ds.flush()
  }
}

class BinaryInputArchive(val is: InputStream) {
  val ds = new DataInputStream(is)

  def readRecord(): QuorumPacket = {
    val recordType = ds.readInt()
    val zxid = ds.readLong()
    val dataSize = ds.readInt()
    val data = new Array[Byte](dataSize)
    ds.read(data)
    QuorumPacket(recordType, zxid, data)
  }
}

class FollowerHandler(peerSocket: Socket, leader: Leader) extends Thread with Logging {
  val oa = new BinaryOutputArchive(new BufferedOutputStream(peerSocket.getOutputStream))
  val ia = new BinaryInputArchive(new BufferedInputStream(peerSocket.getInputStream()))
  val proposalOfDeath = QuorumPacket(Leader.PROPOSAL, 0, Array[Byte]())
  val leaderLastZxid: Long = 0 //TODO
  private val queuedPackets = new LinkedBlockingQueue[QuorumPacket]

  def ping() = {
    val ping = new QuorumPacket(Leader.PING, leader.lastProposed, Array[Byte]())
    queuePacket(ping)
  }

  def queuePacket(p: QuorumPacket) = {
    queuedPackets.add(p);
  }

  def synced() = true //TODO

  override def run(): Unit = {
   try {
    val newLeaderQP = new QuorumPacket(Leader.NEWLEADER, leaderLastZxid, Array[Byte]())
    queuedPackets.add(newLeaderQP)

    new Thread() {
      override def run(): Unit = {
        Thread.currentThread.setName("Sender-" + peerSocket.getRemoteSocketAddress)
        try sendPackets()
        catch {
          case e: InterruptedException ⇒
            warn("Interrupted", e)
        }
      }
    }.start()

    while (true) {
      val responsePacket = ia.readRecord()
      info(s"Received response from ${peerSocket} ${responsePacket}")
    }
   } catch {
     case e:Exception ⇒ error(s"Error while handling followers ${e}")
   }
  }

  def sendPackets() = {
    Breaks.breakable {
      while (true) {
        val p = queuedPackets.take();
        if (p == proposalOfDeath) {
          // Packet of death!
          Breaks.break;
        }
        //      if (p.getType() == Leader.PING) traceMask = ZooTrace.SERVER_PING_TRACE_MASK
        //      ZooTrace.logQuorumPacket(LOG, traceMask, 'o', p);
        //
        try {
          info(s"Sending packet ${p} from leader to ${peerSocket}")
          oa.writeRecord(p)

        } catch {
          case e: Exception ⇒ {
            error("unexpected exception e ")
            Breaks.break;
          }
        }
      }
    }
  }
}
