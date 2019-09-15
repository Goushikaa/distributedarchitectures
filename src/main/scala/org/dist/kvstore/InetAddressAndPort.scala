package org.dist.kvstore

import java.net.InetAddress

import org.dist.dbgossip.Json

import scala.collection.JavaConverters._

object InetAddressAndPort {
  //FIXME: Remove this.
  def create(hostIp:String, port:Int) = {
    InetAddressAndPort(InetAddress.getByName(hostIp), port)
  }
}

case class InetAddressAndPort(address: InetAddress, port: Int) {
  var defaultPort: Int = 7000
}

