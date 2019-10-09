package org.dist.simplekafka

import java.io._

import org.dist.kvstore.SequenceFile
import org.dist.queue.common.TopicAndPartition
import org.dist.queue.server.Config

import scala.util.{Failure, Success, Try}

class Partition(config:Config, topicAndPartition: TopicAndPartition) {
  val LogFileSuffix = ".log"
  val logFile =
    new File(config.logDirs(0), topicAndPartition.topic + "-" + topicAndPartition.partition + LogFileSuffix)

  val file = new SequenceFile()
  val reader = new file.Reader(logFile.getAbsolutePath)
  val writer = new file.Writer(logFile.getAbsolutePath)


  def makeFollower(leaderId:Int) = {
    //TODO: create  a fetcher
  }

  def makeLeader() = {

  }

  def append(key:String, message:String) = {
    val currentPos = writer.getCurrentPosition
    val ba = new ByteArrayOutputStream()
    val bufOut = new DataOutputStream(ba)
    val row = Row(key, message)
    Row.serialize(row, bufOut)
    try writer.append(row.key, ba.toByteArray)
    catch {
      case e: IOException =>
        writer.seek(currentPos)
        throw e
    }
  }

  def read(offset:Long = 0) = {
    val ba = new ByteArrayOutputStream()
    val baos = new DataOutputStream(ba)

    reader.seekToOffset(offset)
    reader.next(baos)

    val bais = new DataInputStream(new ByteArrayInputStream(ba.toByteArray))
    Try(Row.deserialize(bais)) match {
      case Success(row) => Some(row)
      case Failure(exception) => None
    }

  }


  object Row {
    def serialize(row: Row, dos:DataOutputStream): Unit = {
      dos.writeUTF(row.key)
      dos.writeInt(row.value.getBytes().size)
      dos.write(row.value.getBytes) //TODO: as of now only supporting string writes.
    }

    def deserialize(dis: DataInputStream): Row = {
      val key = dis.readUTF()
      val dataSize = dis.readInt()
      val bytes = new Array[Byte](dataSize)
      dis.read(bytes)
      val value = new String(bytes) //TODO:As of now supporting only string values
      Row(key, value)
    }
  }

  case class Row(key: String, value: String)
}
