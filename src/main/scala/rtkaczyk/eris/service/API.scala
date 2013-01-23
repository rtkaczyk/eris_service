package rtkaczyk.eris.service

import rtkaczyk.eris.api.IErisApi
import java.util.{ ArrayList => JArrayList }
import scala.collection.JavaConversions._
import rtkaczyk.eris.api.Common
import rtkaczyk.eris.api.Packet
import android.util.Log
import rtkaczyk.eris.service.storage.QueryCache
import scala.actors.Actor.{actor, self, receive}
import rtkaczyk.eris.api.Events._

class API(val service: ErisService) extends IErisApi.Stub with Common {
  lazy val storage = service.storage
  lazy val discovery = service.discovery
  lazy val receiveR = service.receiver
  lazy val forwarder = service.forwarder
  
  override def xmlConfigure(xml: String) {
    Log.d(TAG, "API: xmlConfigure")
    Config.xml = xml
    service.reconfigure
  }
  
  override def getXmlConfig() = {
    Log.d(TAG, "API: getXmlConfig")
    Config.xml
  }

  override def selectPackets(from: Long, to: Long, device: String, limit: Int): Int = {
    Log.d(TAG, "API: selectPackets")
    val id = storage.nextQueryId
    storage ! Msg.SelectPackets(id, from, to, device, limit)
    id
  }
  
  override def getQuery(id: Int): JArrayList[Packet] = {
    Log.d(TAG, "API: getQuery")
    QueryCache(id) match {
      case Some(packets) => new JArrayList(packets)
      case None => null
    }
  }
  
  override def countPackets {
    Log.d(TAG, "API: countPackets")
    actor {
      storage ! Msg.CountPackets(self)
      receive {
        case Msg.PacketsCount(n) =>
          service !! PacketsCount(n)
      }
    }
  }
  
  override def clearStorage {
    Log.d(TAG, "API: clearStorage")
    storage ! Msg.DeletePackets(0, 0, "")
  }
  
  override def clearCache {
    Log.d(TAG, "API: clearCache")
    storage ! Msg.ClearCache
  }
  
  override def isDiscovering: Boolean = {
    Log.d(TAG, "API: isDiscovering")
    discovery.inProgress
  }
  
  override def startDiscovery: Boolean = {
    Log.d(TAG, "API: startDiscovery")
    val ok = !discovery.inProgress && !receiveR.inProgress
    if (ok)
      discovery ! Msg.StartDiscovery
    ok
  }
  
  override def cancelDiscovery {
    Log.d(TAG, "API: cancelDiscovery")
    discovery ! Msg.StopDiscovery
  }
  
  override def isReceiving: Boolean = {
    Log.d(TAG, "API: isReceiving")
    receiveR.inProgress
  }
  
  override def receivePackets(device: String, from: Long, to: Long, limit: Int) = {
    Log.d(TAG, "API: receivePackets")
    val ok = !discovery.inProgress && !receiveR.inProgress
    if (ok)
      receiveR ! Msg.ReceiverConnect(device, from, to, limit)
    ok
  }
  
  override def cancelReceiving {
    Log.d(TAG, "API: cancelReceiving")
    receiveR ! Msg.ReceiverDisconnect
  }
  
  override def isForwarding: Boolean = {
    Log.d(TAG, "API: isForwarding")
    forwarder.inProgress
  }
  
  override def forwardPackets: Boolean = {
    Log.d(TAG, "API: forwardPackets")
    val ok = !forwarder.inProgress
    if (ok)
      forwarder ! Msg.ForwardPackets(true)
    ok
  }
  
  override def cancelForwarding {
    Log.d(TAG, "API: cancelForwarding")
    forwarder ! Msg.CancelForwarding
  }
}